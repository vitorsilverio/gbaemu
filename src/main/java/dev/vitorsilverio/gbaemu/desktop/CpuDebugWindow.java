package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpsrRegister;
import dev.vitorsilverio.gbaemu.core.GbaConsole;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/// Live view of the ARM7TDMI core: r0-r15, CPSR/flags, mode and the interrupt registers.
/// Reads from whichever {@link GbaConsole} the supplier currently returns, so it keeps
/// working across ROM reloads, and refreshes a few times a second.
public final class CpuDebugWindow {
    private static final String[] REGISTER_LABELS = {
            "r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7",
            "r8", "r9", "r10", "r11", "r12", "r13 (sp)", "r14 (lr)", "r15 (pc)"
    };

    private final Supplier<GbaConsole> consoleSupplier;
    private final JFrame frame = new JFrame("CPU registers");
    private final Map<String, JTextField> fields = new LinkedHashMap<>();
    private final Timer refreshTimer = new Timer(250, event -> refresh());

    public CpuDebugWindow(Supplier<GbaConsole> consoleSupplier) {
        this.consoleSupplier = consoleSupplier;
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setContentPane(buildContent());
        frame.pack();
        frame.setLocationRelativeTo(null);
        refresh();
        frame.setVisible(true);
        refreshTimer.start();
    }

    /// Re-shows and re-focuses an already-created window (used when the menu item is
    /// chosen again after the window was closed/hidden).
    public void present() {
        frame.setVisible(true);
        frame.toFront();
    }

    private JPanel buildContent() {
        JPanel panel = new JPanel(new GridLayout(0, 4, 6, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        for (String label : REGISTER_LABELS) {
            addField(panel, label);
        }
        for (String label : new String[]{"CPSR", "flags", "mode", "state",
                "halted", "stopped", "irqLine", "IE", "IF", "IME", "cycles"}) {
            addField(panel, label);
        }
        return panel;
    }

    private void addField(JPanel panel, String name) {
        panel.add(new JLabel(name));
        JTextField field = new JTextField(9);
        field.setEditable(false);
        field.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        fields.put(name, field);
        panel.add(field);
    }

    private void refresh() {
        GbaConsole console = consoleSupplier.get();
        if (console == null) {
            fields.values().forEach(field -> field.setText("-"));
            return;
        }
        ArmCore cpu = console.cpu();
        int[] registers = cpu.registersSnapshot();
        for (int i = 0; i < REGISTER_LABELS.length && i < registers.length; i++) {
            set(REGISTER_LABELS[i], "%08X", registers[i]);
        }
        CpsrRegister cpsr = cpu.cpsr();
        set("CPSR", "%08X", cpsr.get());
        fields.get("flags").setText(String.format("%s%s%s%s %s%s%s",
                cpsr.negative() ? "N" : "-",
                cpsr.zero() ? "Z" : "-",
                cpsr.carry() ? "C" : "-",
                cpsr.overflow() ? "V" : "-",
                cpsr.irqDisabled() ? "I" : "-",
                cpsr.fiqDisabled() ? "F" : "-",
                cpsr.isThumbMode() ? "T" : "-"));
        fields.get("mode").setText(cpu.mode().name());
        fields.get("state").setText(cpsr.isThumbMode() ? "THUMB" : "ARM");
        fields.get("halted").setText(Boolean.toString(cpu.halted()));
        fields.get("stopped").setText(Boolean.toString(cpu.stopped()));
        fields.get("irqLine").setText(Boolean.toString(cpu.interruptLine()));
        set("IE", "%04X", console.bus().read16(0x04000200));
        set("IF", "%04X", console.bus().read16(0x04000202));
        set("IME", "%04X", console.bus().read16(0x04000208));
        fields.get("cycles").setText(Long.toString(cpu.cycles()));
    }

    private void set(String name, String format, Object value) {
        fields.get(name).setText(String.format(format, value));
    }
}
