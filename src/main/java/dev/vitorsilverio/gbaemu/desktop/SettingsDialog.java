package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.gbaemu.controller.GamepadController;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.function.Consumer;

/// Modal preferences dialog. Edits a working copy of {@link AppSettings} and, on OK, hands the
/// result to the supplied callback (which persists it and applies it live). The General tab
/// holds video/audio/boot options; the Controls tab rebinds the keyboard and configures a
/// gamepad (mappings captured via {@link GamepadController}).
public final class SettingsDialog extends JDialog {
    private final JSpinner scaleSpinner;
    private final JCheckBox muteAudio = new JCheckBox("Mute audio");
    private final JCheckBox scanlineRendering = new JCheckBox("Per-scanline rendering");
    private final JCheckBox debugVideo = new JCheckBox("Log video frame stats");
    private final JComboBox<AppSettings.BootMode> bootMode =
            new JComboBox<>(AppSettings.BootMode.values());
    private final JTextField biosPath = new JTextField(24);

    private final KeyCaptureButton[] controllerKeys = new KeyCaptureButton[AppSettings.BUTTON_COUNT];
    private final JComboBox<String> gamepadDevice = new JComboBox<>();
    private final JSpinner gamepadDeadzone;
    private final JTextField[] gamepadMappings = new JTextField[AppSettings.BUTTON_COUNT];
    private final JButton[] captureButtons = new JButton[AppSettings.BUTTON_COUNT];

    public SettingsDialog(Frame owner, AppSettings current, Consumer<AppSettings> onApply) {
        super(owner, "Settings", true);
        AppSettings settings = current.normalized();
        scaleSpinner = new JSpinner(new SpinnerNumberModel(
                settings.scale(), AppSettings.MIN_SCALE, AppSettings.MAX_SCALE, 1));
        muteAudio.setSelected(settings.muteAudio());
        scanlineRendering.setSelected(settings.scanlineRendering());
        debugVideo.setSelected(settings.debugVideo());
        bootMode.setSelectedItem(settings.bootMode());
        biosPath.setText(settings.biosPath());

        AppSettings.GamepadConfig pad = settings.gamepadConfig();
        gamepadDeadzone = new JSpinner(new SpinnerNumberModel(pad.deadzonePercent(), 0, 95, 1));
        gamepadDevice.addItem("Disabled");
        for (String name : GamepadController.deviceNames()) {
            gamepadDevice.addItem(name);
        }
        setGamepadDeviceSelection(pad.deviceIndex());
        for (int i = 0; i < AppSettings.BUTTON_COUNT; i++) {
            controllerKeys[i] = new KeyCaptureButton(settings.controllerKeyCode(i));
            gamepadMappings[i] = new JTextField(pad.mappings()[i], 22);
            int buttonIndex = i;
            captureButtons[i] = new JButton("Capture");
            captureButtons[i].addActionListener(event -> captureGamepadMapping(buttonIndex));
        }

        setContentPane(buildContent(settings, onApply));
        pack();
        setLocationRelativeTo(owner);
    }

    private JPanel buildContent(AppSettings settings, Consumer<AppSettings> onApply) {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("General", generalPanel());
        tabs.addTab("Controls", controlsPanel());

        JPanel buttons = new JPanel();
        JButton ok = new JButton("OK");
        ok.addActionListener(event -> {
            onApply.accept(collect(settings));
            dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> dispose());
        buttons.add(ok);
        buttons.add(cancel);

        JPanel content = new JPanel(new BorderLayout());
        content.add(tabs, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        return content;
    }

    private JPanel generalPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.anchor = GridBagConstraints.WEST;

        int row = 0;
        addRow(form, constraints, row++, new JLabel("Window scale"), scaleSpinner);
        addRow(form, constraints, row++, new JLabel("Boot mode"), bootMode);
        addRow(form, constraints, row++, new JLabel("BIOS file"), biosFileChooser());
        addWide(form, constraints, row++, muteAudio);
        addWide(form, constraints, row++, scanlineRendering);
        addWide(form, constraints, row++, debugVideo);
        return form;
    }

    private JScrollPane controlsPanel() {
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        int row = 0;
        addSectionLabel(fields, row++, "Keyboard");
        for (int i = 0; i < AppSettings.BUTTON_COUNT; i++) {
            addControlRow(fields, row++, AppSettings.BUTTON_NAMES[i], controllerKeys[i]);
        }
        addSectionLabel(fields, row++, "Gamepad");
        addControlRow(fields, row++, "Device", gamepadDevice);
        addControlRow(fields, row++, "Deadzone %", gamepadDeadzone);
        JButton detected = new JButton("Detected inputs...");
        detected.addActionListener(event -> showGamepadComponents());
        addControlRow(fields, row++, "Diagnostics", detected);
        addHint(fields, row++, "Use Capture to bind the next pressed button or axis.");
        for (int i = 0; i < AppSettings.BUTTON_COUNT; i++) {
            addControlRow(fields, row++, AppSettings.BUTTON_NAMES[i], gamepadMappingPanel(i));
        }

        JScrollPane scrollPane = new JScrollPane(fields);
        scrollPane.setPreferredSize(new Dimension(460, 460));
        return scrollPane;
    }

    private JPanel gamepadMappingPanel(int buttonIndex) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.add(gamepadMappings[buttonIndex], BorderLayout.CENTER);
        panel.add(captureButtons[buttonIndex], BorderLayout.EAST);
        return panel;
    }

    private JPanel biosFileChooser() {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        JButton browse = new JButton("Browse...");
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select GBA BIOS");
            if (!biosPath.getText().isBlank()) {
                File current = new File(biosPath.getText());
                if (current.getParentFile() != null) {
                    chooser.setCurrentDirectory(current.getParentFile());
                }
            }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                biosPath.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        panel.add(biosPath, BorderLayout.CENTER);
        panel.add(browse, BorderLayout.EAST);
        return panel;
    }

    private void setGamepadDeviceSelection(int deviceIndex) {
        int selectedIndex = deviceIndex + 1;
        while (gamepadDevice.getItemCount() <= selectedIndex) {
            gamepadDevice.addItem("#" + gamepadDevice.getItemCount() + " not connected");
        }
        gamepadDevice.setSelectedIndex(Math.max(0, selectedIndex));
    }

    private void showGamepadComponents() {
        int deviceIndex = gamepadDevice.getSelectedIndex() - 1;
        JTextArea content = new JTextArea(GamepadController.componentSnapshot(deviceIndex), 18, 40);
        content.setEditable(false);
        JOptionPane.showMessageDialog(this, new JScrollPane(content),
                "Gamepad inputs", JOptionPane.INFORMATION_MESSAGE);
    }

    private void captureGamepadMapping(int buttonIndex) {
        int deviceIndex = gamepadDevice.getSelectedIndex() - 1;
        if (deviceIndex < 0) {
            JOptionPane.showMessageDialog(this, "Select a gamepad device before capturing.",
                    "Gamepad capture", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JButton captureButton = captureButtons[buttonIndex];
        captureButton.setEnabled(false);
        captureButton.setText("Press...");
        int deadzone = (int) gamepadDeadzone.getValue();
        Thread thread = new Thread(() -> {
            String token = GamepadController.captureNextMapping(deviceIndex, deadzone, 5000L);
            SwingUtilities.invokeLater(() -> {
                captureButton.setEnabled(true);
                captureButton.setText("Capture");
                if (token.isBlank()) {
                    JOptionPane.showMessageDialog(this, "No input detected before timeout.",
                            "Gamepad capture", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                gamepadMappings[buttonIndex].setText(token);
            });
        }, "gbaemu-gamepad-capture");
        thread.setDaemon(true);
        thread.start();
    }

    private AppSettings collect(AppSettings base) {
        int[] keyCodes = new int[AppSettings.BUTTON_COUNT];
        String[] mappings = new String[AppSettings.BUTTON_COUNT];
        for (int i = 0; i < AppSettings.BUTTON_COUNT; i++) {
            keyCodes[i] = controllerKeys[i].keyCode();
            mappings[i] = gamepadMappings[i].getText();
        }
        int deviceIndex = gamepadDevice.getSelectedIndex() - 1;
        AppSettings.GamepadConfig gamepad = new AppSettings.GamepadConfig(
                deviceIndex,
                GamepadController.deviceProfileKey(deviceIndex),
                (int) gamepadDeadzone.getValue(),
                mappings);
        return new AppSettings(
                (Integer) scaleSpinner.getValue(),
                muteAudio.isSelected(),
                scanlineRendering.isSelected(),
                debugVideo.isSelected(),
                biosPath.getText().trim(),
                (AppSettings.BootMode) bootMode.getSelectedItem(),
                base.channelVolumes().clone(),
                base.channelMuted().clone(),
                keyCodes,
                gamepad,
                base.multiplayerTcpHost(),
                base.multiplayerTcpPort(),
                base.multiplayerHostMode()).normalized();
    }

    private void addRow(JPanel form, GridBagConstraints constraints, int row, JLabel label, java.awt.Component field) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0;
        constraints.gridwidth = 1;
        constraints.fill = GridBagConstraints.NONE;
        form.add(label, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, constraints);
    }

    private void addWide(JPanel form, GridBagConstraints constraints, int row, java.awt.Component field) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, constraints);
        constraints.gridwidth = 1;
    }

    private void addControlRow(JPanel panel, int row, String label, java.awt.Component field) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = row;
        constraints.insets = new Insets(3, 6, 3, 6);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.gridx = 0;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, constraints);
    }

    private void addSectionLabel(JPanel panel, int row, String text) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 2;
        constraints.insets = new Insets(10, 6, 4, 6);
        constraints.anchor = GridBagConstraints.WEST;
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
        panel.add(label, constraints);
    }

    private void addHint(JPanel panel, int row, String text) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 2;
        constraints.insets = new Insets(0, 6, 6, 6);
        constraints.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(text), constraints);
    }

    /// A button that shows a key name and, when clicked, captures the next key press as its
    /// new binding.
    private static final class KeyCaptureButton extends JButton {
        private int keyCode;

        private KeyCaptureButton(int keyCode) {
            this.keyCode = keyCode;
            setFocusable(true);
            updateText();
            addActionListener(event -> {
                setText("Press key...");
                requestFocusInWindow();
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent event) {
                    KeyCaptureButton.this.keyCode = event.getKeyCode();
                    updateText();
                }
            });
        }

        private int keyCode() {
            return keyCode;
        }

        private void updateText() {
            setText(KeyEvent.getKeyText(keyCode));
        }
    }
}
