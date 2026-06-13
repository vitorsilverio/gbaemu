package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.gbaemu.audio.GbaAudioChannelSnapshot;
import dev.vitorsilverio.gbaemu.audio.GbaAudioSnapshot;
import dev.vitorsilverio.gbaemu.core.GbaConsole;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.Supplier;

/// Per-channel audio mixer and live state, for diagnosing which channel is misbehaving.
/// Each of the 6 channels (CH1-CH4 PSG, Direct Sound A/B) gets a mute toggle and a
/// volume slider; changes are routed through {@link ChannelControl} so the orchestrator
/// applies them to the live sound unit and persists them. The state table refreshes a
/// few times a second from {@link dev.vitorsilverio.gbaemu.audio.GbaAudio#debugSnapshot()}.
public final class AudioDebugWindow {
    /// Receives the user's per-channel changes (1-based channel index).
    public interface ChannelControl {
        void setMuted(int channel, boolean muted);

        void setVolume(int channel, int percent);
    }

    private static final String[] CHANNEL_NAMES = {
            "CH1 Pulse", "CH2 Pulse", "CH3 Wave", "CH4 Noise", "Direct A", "Direct B"
    };

    private final Supplier<GbaConsole> consoleSupplier;
    private final JFrame frame = new JFrame("Audio channels");
    private final JLabel masterState = new JLabel(" ");
    private final DefaultTableModel stateModel = new DefaultTableModel(
            new Object[]{"CH", "On", "Level", "Hz", "Muted", "Vol%", "Detail"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final Timer refreshTimer = new Timer(500, event -> refresh());

    public AudioDebugWindow(Supplier<GbaConsole> consoleSupplier, AppSettings initial, ChannelControl control) {
        this.consoleSupplier = consoleSupplier;
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(masterState, BorderLayout.NORTH);
        content.add(buildMixer(initial, control), BorderLayout.CENTER);

        JTable stateTable = new JTable(stateModel);
        stateTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane stateScroll = new JScrollPane(stateTable);
        stateScroll.setBorder(BorderFactory.createTitledBorder("Live channel state"));
        content.add(stateScroll, BorderLayout.SOUTH);

        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setContentPane(content);
        frame.pack();
        frame.setLocationRelativeTo(null);
        refresh();
        frame.setVisible(true);
        refreshTimer.start();
    }

    /// Re-shows and re-focuses an already-created window.
    public void present() {
        frame.setVisible(true);
        frame.toFront();
    }

    private JPanel buildMixer(AppSettings initial, ChannelControl control) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Mixer"));
        for (int channel = 1; channel <= CHANNEL_NAMES.length; channel++) {
            addChannelRow(panel, channel, initial, control);
        }
        return panel;
    }

    private void addChannelRow(JPanel panel, int channel, AppSettings initial, ChannelControl control) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = channel - 1;
        constraints.insets = new Insets(3, 4, 3, 4);
        constraints.anchor = GridBagConstraints.WEST;

        constraints.gridx = 0;
        panel.add(new JLabel(CHANNEL_NAMES[channel - 1]), constraints);

        JSlider volume = new JSlider(0, 100, initial.channelVolume(channel));
        volume.setMajorTickSpacing(25);
        volume.setPaintTicks(true);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(volume, constraints);

        JLabel value = new JLabel(volume.getValue() + "%");
        constraints.gridx = 2;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        panel.add(value, constraints);

        JCheckBox mute = new JCheckBox("Mute", initial.isChannelMuted(channel));
        constraints.gridx = 3;
        panel.add(mute, constraints);

        int channelId = channel;
        volume.addChangeListener(event -> {
            value.setText(volume.getValue() + "%");
            control.setVolume(channelId, volume.getValue());
        });
        mute.addActionListener(event -> control.setMuted(channelId, mute.isSelected()));
    }

    private void refresh() {
        GbaConsole console = consoleSupplier.get();
        if (console == null) {
            masterState.setText("No ROM loaded.");
            stateModel.setRowCount(0);
            return;
        }
        GbaAudioSnapshot snapshot = console.audio().debugSnapshot();
        masterState.setText(String.format(
                "master=%s  SOUNDCNT_L=%04X H=%04X X=%04X  BIAS=%04X  rate=%dHz  buffered=%d  fifoA=%d fifoB=%d",
                snapshot.masterEnabled() ? "ON" : "off",
                snapshot.soundCntL(), snapshot.soundCntH(), snapshot.soundCntX(), snapshot.soundBias(),
                snapshot.sampleRate(), snapshot.bufferedSamples(), snapshot.fifoASize(), snapshot.fifoBSize()));
        refreshStateRows(snapshot.channels());
    }

    private void refreshStateRows(List<GbaAudioChannelSnapshot> channels) {
        stateModel.setRowCount(0);
        for (GbaAudioChannelSnapshot channel : channels) {
            stateModel.addRow(new Object[]{
                    channel.name(),
                    channel.enabled() ? "yes" : "no",
                    channel.level(),
                    channel.frequencyHz() <= 0 ? "-" : String.format("%.1f", channel.frequencyHz()),
                    channel.muted() ? "yes" : "no",
                    channel.userVolumePercent(),
                    channel.detail()
            });
        }
    }
}
