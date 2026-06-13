package dev.vitorsilverio.gbaemu.controller;

import dev.vitorsilverio.gbaemu.desktop.AppSettings;
import dev.vitorsilverio.gbaemu.input.GbaButton;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/// Reads a physical gamepad through the optional input4j library, entirely via reflection so
/// the emulator compiles and runs even when input4j is absent (the pad just stays inert). A
/// daemon thread polls the device and publishes button state as a volatile bitmask; the
/// emulator thread reads it each frame. Adapted from the gbcemu GamepadController, keyed on
/// {@link GbaButton} (10 buttons, incl. L/R) instead of the Game Boy's 8.
public final class GamepadController implements Controller, AutoCloseable {

    private static final int BUTTON_COUNT = GbaButton.values().length;
    private static final long POLL_INTERVAL_MILLIS = 4L;
    private static final long FAILURE_RETRY_MILLIS = 16L;
    private static final long DIGITAL_RELEASE_GRACE_NANOS = 72_000_000L;
    private static final long ANALOG_RELEASE_GRACE_NANOS = 24_000_000L;
    private static final int MAX_TRANSIENT_FAILURES = 8;

    private static volatile Object inputDevices;
    private static volatile boolean input4jUnavailable;

    private volatile AppSettings.GamepadConfig config;
    private volatile boolean running;
    private Thread pollThread;
    private int consecutivePollFailures;
    private volatile int pressedBits;
    private final long[] releaseAt = new long[BUTTON_COUNT];

    public GamepadController(AppSettings.GamepadConfig config) {
        this.config = config;
        start();
    }

    public void applySettings(AppSettings.GamepadConfig config) {
        this.config = config;
        releaseAll();
    }

    private void start() {
        if (inputDevices() == null) {
            return;
        }
        running = true;
        pollThread = new Thread(this::pollLoop, "gbaemu-gamepad");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    @Override
    public boolean isPressed(GbaButton button) {
        return (pressedBits & (1 << button.ordinal())) != 0;
    }

    @Override
    public void close() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
        }
        releaseAll();
    }

    // ---- Static discovery helpers (used by the settings dialog) --------------------------

    public static List<String> deviceNames() {
        Object devices = inputDevices();
        if (devices == null) {
            return List.of();
        }
        try {
            Object result = devices.getClass().getMethod("getAll").invoke(devices);
            if (!(result instanceof Collection<?> collection)) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            int index = 1;
            for (Object device : collection) {
                names.add("#" + index + " " + deviceName(device));
                index++;
            }
            return names;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }

    /// The product name of the device at {@code deviceIndex}, stored alongside the index so a
    /// pad can be re-matched by name if it enumerates in a different order next launch.
    public static String deviceProfileKey(int deviceIndex) {
        if (deviceIndex < 0) {
            return "";
        }
        Object devices = inputDevices();
        if (devices == null) {
            return "";
        }
        try {
            Object result = devices.getClass().getMethod("getAll").invoke(devices);
            if (!(result instanceof Collection<?> collection) || collection.size() <= deviceIndex) {
                return "";
            }
            return deviceName(new ArrayList<>(collection).get(deviceIndex));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /// A human-readable dump of every component's current value, for the settings "Detected
    /// inputs" diagnostic.
    public static String componentSnapshot(int deviceIndex) {
        if (deviceIndex < 0) {
            return "Gamepad disabled.";
        }
        Object devices = inputDevices();
        if (devices == null) {
            return "input4j unavailable.";
        }
        try {
            Object result = devices.getClass().getMethod("getAll").invoke(devices);
            if (!(result instanceof Collection<?> collection) || collection.size() <= deviceIndex) {
                return "Device not found.";
            }
            Object device = new ArrayList<>(collection).get(deviceIndex);
            device.getClass().getMethod("poll").invoke(device);
            Object componentsResult = device.getClass().getMethod("getComponents").invoke(device);
            if (!(componentsResult instanceof Collection<?> components)) {
                return "No components.";
            }
            StringBuilder builder = new StringBuilder();
            for (Object component : components) {
                builder.append(componentNameStatic(component)).append(" = ")
                        .append(String.format(Locale.ROOT, "%.3f", componentValueStatic(component))).append('\n');
            }
            return builder.isEmpty() ? "No components." : builder.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "Failed to read components: " + e.getMessage();
        }
    }

    /// Watches the device until one component crosses the deadzone, returning its token
    /// (e.g. {@code "BUTTON_0"} or {@code "AXIS_X+"}), or "" on timeout. Used to capture a mapping.
    public static String captureNextMapping(int deviceIndex, int deadzonePercent, long timeoutMillis) {
        if (deviceIndex < 0) {
            return "";
        }
        Object devices = inputDevices();
        if (devices == null) {
            return "";
        }
        try {
            Object result = devices.getClass().getMethod("getAll").invoke(devices);
            if (!(result instanceof Collection<?> collection) || collection.size() <= deviceIndex) {
                return "";
            }
            Object device = new ArrayList<>(collection).get(deviceIndex);
            float threshold = Math.max(0.05f, Math.min(0.95f, deadzonePercent / 100.0f));
            List<ComponentReading> baseline = componentReadings(device);
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                Thread.sleep(20L);
                String token = changedComponentToken(baseline, componentReadings(device), threshold);
                if (!token.isBlank()) {
                    return token;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return "";
    }

    private static Object inputDevices() {
        if (input4jUnavailable) {
            return null;
        }
        Object current = inputDevices;
        if (current != null) {
            return current;
        }
        synchronized (GamepadController.class) {
            if (inputDevices != null) {
                return inputDevices;
            }
            try {
                Class<?> inputDevicesClass = Class.forName("de.gurkenlabs.input4j.InputDevices");
                inputDevices = inputDevicesClass.getMethod("init").invoke(null);
                if (inputDevices == null) {
                    input4jUnavailable = true;
                    return null;
                }
                System.out.println("input4j gamepad support initialized");
                return inputDevices;
            } catch (Throwable e) {
                input4jUnavailable = true;
                System.out.println("input4j gamepad support unavailable: " + e);
                return null;
            }
        }
    }

    // ---- Poll loop -----------------------------------------------------------------------

    private void pollLoop() {
        while (running) {
            try {
                pollOnce();
                consecutivePollFailures = 0;
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                consecutivePollFailures++;
                if (consecutivePollFailures >= MAX_TRANSIENT_FAILURES) {
                    pressedBits = 0;
                }
                sleepAfterFailure();
            }
        }
    }

    private void pollOnce() throws ReflectiveOperationException {
        Object device = deviceForPlayer();
        if (device == null) {
            pressedBits = 0;
            return;
        }
        device.getClass().getMethod("poll").invoke(device);
        applyState(readState(components(device)));
    }

    private Object deviceForPlayer() throws ReflectiveOperationException {
        Object devices = inputDevices();
        if (devices == null) {
            return null;
        }
        AppSettings.GamepadConfig cfg = config;
        int deviceIndex = cfg == null ? -1 : cfg.deviceIndex();
        if (deviceIndex < 0) {
            return null;
        }
        List<?> all = new ArrayList<>(allDevices(devices));
        String wantedName = cfg == null || cfg.deviceName() == null ? "" : cfg.deviceName();
        if (!wantedName.isBlank()) {
            if (deviceIndex < all.size() && wantedName.equals(deviceName(all.get(deviceIndex)))) {
                return all.get(deviceIndex);
            }
            for (Object device : all) {
                if (wantedName.equals(deviceName(device))) {
                    return device;
                }
            }
        }
        return deviceIndex < all.size() ? all.get(deviceIndex) : null;
    }

    private Collection<?> allDevices(Object devices) throws ReflectiveOperationException {
        Object result = devices.getClass().getMethod("getAll").invoke(devices);
        return result instanceof Collection<?> collection ? collection : List.of();
    }

    private Collection<?> components(Object device) throws ReflectiveOperationException {
        Object result = device.getClass().getMethod("getComponents").invoke(device);
        return result instanceof Collection<?> collection ? collection : List.of();
    }

    private PadState readState(Collection<?> components) {
        boolean[] pressed = new boolean[BUTTON_COUNT];
        boolean analogDirection = false;
        AppSettings.GamepadConfig cfg = config;
        String[] mappings = cfg == null || cfg.mappings() == null
                ? AppSettings.defaults().gamepadConfig().mappings()
                : cfg.mappings();
        float deadzone = cfg == null ? 0.35f : cfg.deadzonePercent() / 100.0f;
        for (Object component : components) {
            String name = componentName(component);
            float value = componentValue(component);
            for (GbaButton button : GbaButton.values()) {
                int i = button.ordinal();
                if (i < mappings.length && matchesMapping(name, value, mappings[i], deadzone)) {
                    pressed[i] = true;
                }
            }
            boolean up = false, down = false, left = false, right = false;
            if (matches(name, "LEFT_THUMB_X", "LEFT_AXIS_X", "AXIS_X")) {
                left = value < -deadzone;
                right = value > deadzone;
            } else if (matches(name, "LEFT_THUMB_Y", "LEFT_AXIS_Y", "AXIS_Y")) {
                up = value < -deadzone;
                down = value > deadzone;
            } else if (matches(name, "DPAD", "DPAD_AXIS")) {
                up = value == 0.25f || value == 0.125f || value == 0.375f;
                right = value == 0.5f || value == 0.375f || value == 0.625f;
                down = value == 0.75f || value == 0.625f || value == 0.875f;
                left = value == 1.0f || value == 0.875f || value == 0.125f;
            }
            pressed[GbaButton.UP.ordinal()] |= up;
            pressed[GbaButton.DOWN.ordinal()] |= down;
            pressed[GbaButton.LEFT.ordinal()] |= left;
            pressed[GbaButton.RIGHT.ordinal()] |= right;
            analogDirection |= up || down || left || right;
        }
        return new PadState(pressed, analogDirection);
    }

    /// Applies a freshly read state with a short release grace, so a momentary analog/dpad
    /// flicker doesn't drop the button. Recomputes the whole bitmask each poll.
    private void applyState(PadState state) {
        long now = System.nanoTime();
        int bits = 0;
        for (GbaButton button : GbaButton.values()) {
            int i = button.ordinal();
            if (state.pressed[i]) {
                releaseAt[i] = now + (isDirection(button) && state.anyAnalog
                        ? ANALOG_RELEASE_GRACE_NANOS
                        : DIGITAL_RELEASE_GRACE_NANOS);
            }
            if (state.pressed[i] || now < releaseAt[i]) {
                bits |= (1 << i);
            }
        }
        pressedBits = bits;
    }

    private static boolean isDirection(GbaButton button) {
        return button == GbaButton.UP || button == GbaButton.DOWN
                || button == GbaButton.LEFT || button == GbaButton.RIGHT;
    }

    private void sleepAfterFailure() {
        try {
            Thread.sleep(FAILURE_RETRY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void releaseAll() {
        pressedBits = 0;
        Arrays.fill(releaseAt, 0L);
        consecutivePollFailures = 0;
    }

    // ---- Reflection name/value plumbing --------------------------------------------------

    private String componentName(Object component) {
        return componentNameStatic(component);
    }

    private static String componentNameStatic(Object component) {
        Object id = invokeOptionalStatic(component, "getId", "id", "getName", "name");
        String name = (id == null ? component : id).toString();
        return name == null ? "" : name.toUpperCase(Locale.ROOT);
    }

    private static String deviceName(Object device) {
        Object name = invokeOptionalStatic(device, "getName", "name", "getProductName", "productName");
        return name == null ? device.toString() : name.toString().strip();
    }

    private float componentValue(Object component) {
        return componentValueStatic(component);
    }

    private static float componentValueStatic(Object component) {
        Object value = invokeOptionalStatic(component, "getData", "data", "getValue", "value", "isPressed", "pressed");
        if (value instanceof Number number) {
            return number.floatValue();
        }
        if (value instanceof Boolean pressed) {
            return pressed ? 1.0f : 0.0f;
        }
        return 0.0f;
    }

    private static List<ComponentReading> componentReadings(Object device) throws ReflectiveOperationException {
        device.getClass().getMethod("poll").invoke(device);
        Object componentsResult = device.getClass().getMethod("getComponents").invoke(device);
        if (!(componentsResult instanceof Collection<?> components)) {
            return List.of();
        }
        List<ComponentReading> readings = new ArrayList<>();
        for (Object component : components) {
            readings.add(new ComponentReading(componentNameStatic(component), componentValueStatic(component)));
        }
        return readings;
    }

    private static String changedComponentToken(List<ComponentReading> baseline, List<ComponentReading> current, float threshold) {
        for (ComponentReading reading : current) {
            float previous = baselineValue(baseline, reading.name());
            if (Math.abs(reading.value() - previous) < threshold || Math.abs(reading.value()) < threshold) {
                continue;
            }
            if (isAxisLike(reading.name())) {
                return reading.name() + (reading.value() < 0 ? "-" : "+");
            }
            return reading.name();
        }
        return "";
    }

    private static float baselineValue(List<ComponentReading> baseline, String name) {
        for (ComponentReading reading : baseline) {
            if (reading.name().equals(name)) {
                return reading.value();
            }
        }
        return 0.0f;
    }

    private static boolean isAxisLike(String name) {
        return name.contains("AXIS") || name.contains("THUMB") || name.contains("STICK")
                || name.contains("TRIGGER") || name.endsWith("_X") || name.endsWith("_Y");
    }

    private static Object invokeOptionalStatic(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                return method.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private boolean matchesMapping(String actual, float value, String mapping, float deadzone) {
        if (mapping == null || mapping.isBlank()) {
            return false;
        }
        return Arrays.stream(mapping.split(","))
                .map(String::strip)
                .filter(token -> !token.isBlank())
                .anyMatch(token -> matchesToken(actual, value, token.toUpperCase(Locale.ROOT), deadzone));
    }

    private boolean matchesToken(String actual, float value, String token, float deadzone) {
        if (token.endsWith("+")) {
            return matches(actual, token.substring(0, token.length() - 1)) && value > deadzone;
        }
        if (token.endsWith("-")) {
            return matches(actual, token.substring(0, token.length() - 1)) && value < -deadzone;
        }
        return matches(actual, token) && value > 0.5f;
    }

    private boolean matches(String actual, String... expected) {
        for (String candidate : expected) {
            if (actual.equals(candidate) || actual.endsWith("_" + candidate)) {
                return true;
            }
        }
        return false;
    }

    private record PadState(boolean[] pressed, boolean anyAnalog) {
    }

    private record ComponentReading(String name, float value) {
    }
}
