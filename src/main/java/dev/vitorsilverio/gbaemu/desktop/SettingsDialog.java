package dev.vitorsilverio.gbaemu.desktop;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.function.Consumer;

/// Modal preferences dialog. Edits a working copy of {@link AppSettings} and, on OK,
/// hands the result to the supplied callback (which persists it and applies it live).
/// Boot mode, BIOS path and mute take effect when the next ROM is loaded or restarted;
/// the scale applies immediately.
public final class SettingsDialog extends JDialog {
    private final JSpinner scaleSpinner;
    private final JCheckBox muteAudio = new JCheckBox("Mute audio");
    private final JCheckBox scanlineRendering = new JCheckBox("Per-scanline rendering");
    private final JCheckBox debugVideo = new JCheckBox("Log video frame stats");
    private final JComboBox<AppSettings.BootMode> bootMode =
            new JComboBox<>(AppSettings.BootMode.values());
    private final JTextField biosPath = new JTextField(24);

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

        setContentPane(buildContent(settings, onApply));
        pack();
        setLocationRelativeTo(owner);
    }

    private JPanel buildContent(AppSettings settings, Consumer<AppSettings> onApply) {
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
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        return content;
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

    private AppSettings collect(AppSettings base) {
        return new AppSettings(
                (Integer) scaleSpinner.getValue(),
                muteAudio.isSelected(),
                scanlineRendering.isSelected(),
                debugVideo.isSelected(),
                biosPath.getText().trim(),
                (AppSettings.BootMode) bootMode.getSelectedItem(),
                base.channelVolumes().clone(),
                base.channelMuted().clone()).normalized();
    }

    private void addRow(JPanel form, GridBagConstraints constraints, int row, JLabel label, java.awt.Component field) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0;
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
}
