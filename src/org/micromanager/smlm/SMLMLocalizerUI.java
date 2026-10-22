package org.micromanager.smlm;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;


public class SMLMLocalizerUI implements ProgressListener {

    private static final String NO_FOLDER = "(no folder selected)";

    private JFrame frame_;

    // ---- Part 1 ----
    private JSpinner framesSpin_;
    private JSpinner expSpin_;
    private JSpinner pixelSizeSpin_;
    private JSpinner bgSigmaSpin_;
    private JSpinner smoothSigmaSpin_;
    private JSpinner minPeakSpin_;
    private JSpinner peakThreshSpin_;
    private JSpinner roiSizeSpin_;
    private JCheckBox simModeCB_;
    private JTextField simFolderField_;
    private JButton simFolderBtn_;
    private JCheckBox previewDuringAcqCB_;

    // ---- Part 2 ----
    private JCheckBox saveStackCB_;
    private JRadioButton saveMultiPageRB_;
    private JRadioButton savePerFrameRB_;
    private JCheckBox saveReconCB_;
    private JCheckBox saveCsvCB_;
    private JSpinner magSpin_;
    private JSpinner updSpin_;
    private JTextField outField_;
    private JButton outBtn_;

    // ---- run controls / progress ----
    private JButton previewBtn_;
    private JButton startBtn_;
    private JButton stopBtn_;
    private JButton clearBtn_;
    private JProgressBar progressBar_;
    private JLabel etaLabel_;
    private JLabel queueLabel_;
    private JLabel statusLabel_;

    // selected folders (full paths), null = none
    private String outDir_ = null;
    private String simFolder_ = null;

    private PostProcessingUI postUi_ = null;

    // callbacks
    private Consumer<AcquisitionParameters> previewCallback;
    private Consumer<AcquisitionParameters> startCallback;
    private Runnable stopCallback;
    private Runnable clearCallback;

    public SMLMLocalizerUI() {
        createGui();
    }


    private void createGui() {
        frame_ = new JFrame("SMLM Localizer");
        frame_.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame_.setLayout(new BorderLayout(0, 4));

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));
        center.add(createAcquisitionPanel());
        center.add(Box.createVerticalStrut(6));
        center.add(createOutputPanel());
        center.add(Box.createVerticalStrut(6));
        center.add(createPostProcessingPanel());

        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        south.add(createButtonPanel(), BorderLayout.NORTH);
        south.add(createProgressPanel(), BorderLayout.CENTER);

        frame_.add(center, BorderLayout.CENTER);
        frame_.add(south, BorderLayout.SOUTH);

        updateEnabledStates();
        frame_.pack();
        frame_.setResizable(false);
        frame_.setLocationRelativeTo(null);
    }

    /** Part 1 */
    private JPanel createAcquisitionPanel() {
        JPanel p = titledGridPanel("1. Acquisition & localization");
        int r = 0;

        framesSpin_ = intSpinner(1000, 1, 10_000_000, 1);
        addRow(p, r++, "Frames:", framesSpin_);

        expSpin_ = intSpinner(30, 1, 10000, 1);
        addRow(p, r++, "Exposure (ms):", expSpin_);

        pixelSizeSpin_ = doubleSpinner(100.0, 1.0, 10000.0, 1.0);
        pixelSizeSpin_.setToolTipText("Camera pixel size referred to the sample plane (nm). Used to convert the CSV to nm.");
        addRow(p, r++, "Pixel size (nm):", pixelSizeSpin_);

        addSeparator(p, r++);

        bgSigmaSpin_ = doubleSpinner(50.0, 0.1, 500.0, 1.0);
        addRow(p, r++, "Background sigma:", bgSigmaSpin_);

        smoothSigmaSpin_ = doubleSpinner(1.0, 0.0, 10.0, 0.5);
        addRow(p, r++, "Smoothing sigma:", smoothSigmaSpin_);

        minPeakSpin_ = intSpinner(3, 1, 50, 1);
        addRow(p, r++, "Min peak distance:", minPeakSpin_);

        peakThreshSpin_ = doubleSpinner(2.0, 0.0, 1000.0, 0.5);
        addRow(p, r++, "Peak threshold:", peakThreshSpin_);

        roiSizeSpin_ = intSpinner(7, 3, 31, 2);
        roiSizeSpin_.setToolTipText("Odd. The width estimate needs the ROI to cover the whole PSF (about 6 x the largest sigma).");
        addRow(p, r++, "ROI size (odd):", roiSizeSpin_);

        addSeparator(p, r++);

        simModeCB_ = new JCheckBox("Simulation mode (load TIFFs from folder)");
        simModeCB_.addActionListener(e -> updateEnabledStates());
        addWide(p, r++, simModeCB_);

        simFolderField_ = pathField();
        simFolderBtn_ = new JButton("Browse...");
        simFolderBtn_.addActionListener(e -> {
            String f = chooseFolder();
            if (f != null) { simFolder_ = f; showPath(simFolderField_, f); }
        });
        addRow(p, r++, "Sim folder:", pathPanel(simFolderField_, simFolderBtn_));

        previewDuringAcqCB_ = new JCheckBox("Show live preview during acquisition", true);
        addWide(p, r++, previewDuringAcqCB_);

        return p;
    }

    /** Part 2 */
    private JPanel createOutputPanel() {
        JPanel p = titledGridPanel("2. Output");
        int r = 0;

        saveStackCB_ = new JCheckBox("Save acquired stack (raw frames)");
        saveStackCB_.addActionListener(e -> updateEnabledStates());
        addWide(p, r++, saveStackCB_);

        saveMultiPageRB_ = new JRadioButton("Multi-page TIFF (stream)", true);
        savePerFrameRB_ = new JRadioButton("One TIFF per frame (stack.tif is always used)");
        savePerFrameRB_.setEnabled(false);
        ButtonGroup bg = new ButtonGroup();
        bg.add(saveMultiPageRB_);
        bg.add(savePerFrameRB_);
        JPanel radios = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        radios.add(saveMultiPageRB_);
        radios.add(savePerFrameRB_);
        addWide(p, r++, indent(radios));

        saveReconCB_ = new JCheckBox("Save live reconstructed image (live_reconstruction.tif)");
        saveReconCB_.addActionListener(e -> updateEnabledStates());
        addWide(p, r++, saveReconCB_);

        saveCsvCB_ = new JCheckBox("Save localizations CSV (ThunderSTORM-compatible columns)", true);
        saveCsvCB_.addActionListener(e -> updateEnabledStates());
        addWide(p, r++, saveCsvCB_);

        outField_ = pathField();
        outBtn_ = new JButton("Browse...");
        outBtn_.addActionListener(e -> {
            String f = chooseFolder();
            if (f != null) { outDir_ = f; showPath(outField_, f); }
        });
        addRow(p, r++, "Output parent folder:", pathPanel(outField_, outBtn_));
        JLabel folderHint = new JLabel("Each run creates a YYYYMMDD_HH_mm subfolder here.");
        folderHint.setForeground(Color.GRAY);
        folderHint.setFont(folderHint.getFont().deriveFont(10f));
        addWide(p, r++, folderHint);

        addSeparator(p, r++);

        magSpin_ = intSpinner(5, 1, 20, 1);
        magSpin_.setToolTipText("Histogram pixels per camera pixel");
        addRow(p, r++, "Reconstruction mag:", magSpin_);

        updSpin_ = intSpinner(20, 1, 1000, 1);
        addRow(p, r++, "Refresh every N frames:", updSpin_);

        return p;
    }

    /** Part 3: entry point to the post-processing window (nothing here runs during acquisition). */
    private JPanel createPostProcessingPanel() {
        JPanel p = titledGridPanel("3. 3D calibration & rendering (post-processing)");
        JLabel hint = new JLabel("<html>Calibrate z from a bead stack (or load a calibration),<br>"
                + "then compute z for a localization CSV and render the 3D image.</html>");
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN));
        addWide(p, 0, hint);
        JButton open = new JButton("Open 3D tools...");
        open.addActionListener(e -> {
            if (postUi_ == null) postUi_ = new PostProcessingUI();
            postUi_.setDefaultFolder(outDir_);
            postUi_.show();
        });
        addWide(p, 1, open);
        return p;
    }

    private JPanel createButtonPanel() {
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        previewBtn_ = new JButton("Preview (single frame)");
        startBtn_ = new JButton("Start");
        stopBtn_ = new JButton("Stop");
        stopBtn_.setEnabled(false);
        clearBtn_ = new JButton("Clear histogram");
        btnPanel.add(previewBtn_);
        btnPanel.add(startBtn_);
        btnPanel.add(stopBtn_);
        btnPanel.add(clearBtn_);

        previewBtn_.addActionListener(e -> {
            AcquisitionParameters params = readParameters();
            if (params.simulationMode && params.simFolder == null) {
                warn("Select a simulation folder first.");
                return;
            }
            if (previewCallback != null) previewCallback.accept(params);
        });

        startBtn_.addActionListener(e -> {
            AcquisitionParameters params = readParameters();
            String problem = validate(params);
            if (problem != null) { warn(problem); return; }
            setRunning(true);
            statusLabel_.setText("Acquiring...");
            if (startCallback != null) startCallback.accept(params);
        });

        stopBtn_.addActionListener(e -> {
            stopBtn_.setEnabled(false);
            statusLabel_.setText("Stopping - finishing queued frames...");
            if (stopCallback != null) stopCallback.run();
            // Start/Preview are re-enabled in closeProgress() once the worker threads are done.
        });

        clearBtn_.addActionListener(e -> {
            if (clearCallback != null) clearCallback.run();
        });
        return btnPanel;
    }

    private JPanel createProgressPanel() {
        progressBar_ = new JProgressBar(0, 100);
        progressBar_.setStringPainted(true);

        etaLabel_ = new JLabel("ETA: -");
        queueLabel_ = new JLabel("Queue: 0");
        queueLabel_.setFont(queueLabel_.getFont().deriveFont(10f));
        queueLabel_.setForeground(Color.GRAY);
        JPanel right = new JPanel(new GridLayout(2, 1));
        right.add(etaLabel_);
        right.add(queueLabel_);

        statusLabel_ = new JLabel("Idle");
        statusLabel_.setFont(statusLabel_.getFont().deriveFont(Font.ITALIC));

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.add(progressBar_, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);

        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.add(bar, BorderLayout.CENTER);
        p.add(statusLabel_, BorderLayout.SOUTH);
        return p;
    }

    private static JPanel titledGridPanel(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static void addRow(JPanel p, int row, String label, JComponent c) {
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 6, 2, 6);
        g.gridy = row;
        g.gridx = 0; g.anchor = GridBagConstraints.WEST;
        p.add(new JLabel(label), g);
        g.gridx = 1; g.weightx = 1.0; g.anchor = GridBagConstraints.WEST;
        // only the folder rows (panels) stretch; spinners keep their natural width
        g.fill = (c instanceof JPanel) ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        p.add(c, g);
    }

    private static void addWide(JPanel p, int row, JComponent c) {
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 6, 2, 6);
        g.gridy = row; g.gridx = 0; g.gridwidth = 2;
        g.anchor = GridBagConstraints.WEST;
        p.add(c, g);
    }

    private static void addSeparator(JPanel p, int row) {
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.gridy = row; g.gridx = 0; g.gridwidth = 2;
        g.fill = GridBagConstraints.HORIZONTAL;
        p.add(new JSeparator(SwingConstants.HORIZONTAL), g);
    }

    private static JPanel indent(JComponent c) {
        JPanel w = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        w.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 0));
        w.add(c);
        return w;
    }

    private static JSpinner intSpinner(int value, int min, int max, int step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(8);
        return s;
    }

    private static JSpinner doubleSpinner(double value, double min, double max, double step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(8);
        return s;
    }

    private static JTextField pathField() {
        JTextField f = new JTextField(NO_FOLDER, 24);
        f.setEditable(false);
        return f;
    }

    private static JPanel pathPanel(JTextField field, JButton btn) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.add(field, BorderLayout.CENTER);
        p.add(btn, BorderLayout.EAST);
        return p;
    }

    private void showPath(JTextField field, String full) {
        field.setText(truncatePath(full, 48));
        field.setToolTipText(full);
    }

    private String chooseFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(frame_) == JFileChooser.APPROVE_OPTION) {
            return fc.getSelectedFile().getAbsolutePath();
        }
        return null;
    }

    private void warn(String msg) {
        JOptionPane.showMessageDialog(frame_, msg, "SMLM Localizer", JOptionPane.WARNING_MESSAGE);
    }

    private static String truncatePath(String path, int maxLen) {
        if (path == null) return "";
        if (path.length() <= maxLen) return path;
        int keep = Math.max(6, maxLen / 3);
        return path.substring(0, keep) + "..." + path.substring(path.length() - keep);
    }


    private void updateEnabledStates() {
        boolean sim = simModeCB_.isSelected();
        simFolderBtn_.setEnabled(sim);
        simFolderField_.setEnabled(sim);
        expSpin_.setEnabled(!sim);          // exposure is only used with a real camera

        boolean stack = saveStackCB_.isSelected();
        saveMultiPageRB_.setEnabled(stack);
        savePerFrameRB_.setEnabled(stack);

        boolean anySave = stack || saveReconCB_.isSelected() || saveCsvCB_.isSelected();
        outBtn_.setEnabled(anySave);
        outField_.setEnabled(anySave);
    }

    private void setRunning(boolean running) {
        startBtn_.setEnabled(!running);
        previewBtn_.setEnabled(!running);
        stopBtn_.setEnabled(running);
    }

    private String validate(AcquisitionParameters p) {
        if (p.simulationMode && p.simFolder == null) {
            return "Select a simulation folder (or untick simulation mode).";
        }
        if ((p.saveStack || p.saveReconstruction || p.saveCsv) && p.outDir == null) {
            return "Select an output folder, or untick the save options.";
        }
        return null;
    }

    private AcquisitionParameters readParameters() {
        AcquisitionParameters p = new AcquisitionParameters();
        // Part 1
        p.frames = ((Number) framesSpin_.getValue()).intValue();
        p.exposureMs = ((Number) expSpin_.getValue()).intValue();
        p.pixelSizeNm = ((Number) pixelSizeSpin_.getValue()).doubleValue();
        p.backgroundSigma = ((Number) bgSigmaSpin_.getValue()).doubleValue();
        p.smoothingSigma = ((Number) smoothSigmaSpin_.getValue()).doubleValue();
        p.minPeakDistance = ((Number) minPeakSpin_.getValue()).intValue();
        p.peakThreshold = ((Number) peakThreshSpin_.getValue()).doubleValue();
        p.roiSize = ((Number) roiSizeSpin_.getValue()).intValue();
        if ((p.roiSize % 2) == 0) p.roiSize++;
        p.simulationMode = simModeCB_.isSelected();
        p.simFolder = simFolder_;
        p.showLivePreview = previewDuringAcqCB_.isSelected();
        // Part 2
        p.saveStack = saveStackCB_.isSelected();
        p.saveMultipage = saveMultiPageRB_.isSelected();
        p.saveSingleFiles = savePerFrameRB_.isSelected();
        p.saveReconstruction = saveReconCB_.isSelected();
        p.saveCsv = saveCsvCB_.isSelected();
        p.outDir = outDir_;
        p.mag = ((Number) magSpin_.getValue()).intValue();
        p.updateInterval = ((Number) updSpin_.getValue()).intValue();
        return p;
    }


    public void show() {
        frame_.setVisible(true);
        frame_.toFront();
    }

    public void setPreviewCallback(Consumer<AcquisitionParameters> cb) { this.previewCallback = cb; }
    public void setStartCallback(Consumer<AcquisitionParameters> cb) { this.startCallback = cb; }
    public void setStopCallback(Runnable cb) { this.stopCallback = cb; }
    public void setClearCallback(Runnable cb) { this.clearCallback = cb; }


    @Override
    public void initProgress(int totalFrames) {
        SwingUtilities.invokeLater(() -> {
            if (totalFrames <= 0) {
                progressBar_.setIndeterminate(true);
                progressBar_.setString("Starting...");
            } else {
                progressBar_.setIndeterminate(false);
                progressBar_.setMaximum(totalFrames);
                progressBar_.setValue(0);
                progressBar_.setString("0 / " + totalFrames);
            }
            etaLabel_.setText("ETA: calculating...");
            statusLabel_.setText("Acquiring...");
        });
    }

    @Override
    public void updateProgress(int processed, int totalFrames, String etaText) {
        SwingUtilities.invokeLater(() -> {
            if (!progressBar_.isIndeterminate() && totalFrames > 0) {
                progressBar_.setMaximum(Math.max(1, totalFrames));
                progressBar_.setValue(Math.min(processed, totalFrames));
                progressBar_.setString(String.format("%d / %d", processed, totalFrames));
            } else if (progressBar_.isIndeterminate()) {
                progressBar_.setString(processed + " frames");
            }
            etaLabel_.setText("ETA: " + (etaText == null ? "-" : etaText));
        });
    }

    /** Called once everything (acquisition, localization, saving) is finished or aborted. */
    @Override
    public void closeProgress() {
        SwingUtilities.invokeLater(() -> {
            progressBar_.setIndeterminate(false);
            etaLabel_.setText("ETA: done");
            queueLabel_.setText("Queue: 0");
            queueLabel_.setForeground(Color.GRAY);
            statusLabel_.setText("Finished.");
            setRunning(false);
        });
    }

    @Override
    public void onCameraFinished() {
        // non-modal: a dialog here used to block the EDT while frames were still being processed
        SwingUtilities.invokeLater(() -> {
            progressBar_.setValue(progressBar_.getMaximum());
            statusLabel_.setText("Camera finished - localizing remaining frames...");
        });
    }

    @Override
    public void onQueueStatus(int pendingCount) {
        SwingUtilities.invokeLater(() -> {
            queueLabel_.setText("Queue: " + pendingCount);
            queueLabel_.setForeground(pendingCount > 80 ? Color.RED : Color.GRAY);
        });
    }
}
