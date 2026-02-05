package org.micromanager.smlm;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Main plugin UI. Now also implements ProgressListener so acquisition can update progress/ETA here.
 */
public class SMLMLocalizerUI implements ProgressListener {

    private JFrame frame_;
    // controls ...
    private JSpinner framesSpin_;
    private JSpinner expSpin_;
    private JSpinner magSpin_;
    private JSpinner updSpin_;
    private JSpinner bgSigmaSpin_;
    private JSpinner smoothSigmaSpin_;
    private JSpinner minPeakSpin_;
    private JSpinner peakThreshSpin_;
    private JSpinner roiSizeSpin_;
    private JCheckBox saveStackCB_;
    private JCheckBox simModeCB_;
    private JTextField outField_;
    private JTextField simFolderField_;
    private JButton previewBtn_;
    private JButton startBtn_;
    private JButton stopBtn_;
    private JButton clearBtn_;
    private JLabel queueLabel_;

    private JRadioButton saveMultiPageRB_;
    private JRadioButton savePerFrameRB_;
    private JCheckBox previewDuringAcqCB_;

    // progress UI components
    private JProgressBar progressBar_;
    private JLabel etaLabel_;
    private JPanel progressPanel_;

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
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4,4,4,4);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        // Frames
        gbc.gridx = 0; gbc.gridy = row;
        p.add(new JLabel("Frames:"), gbc);
        framesSpin_ = new JSpinner(new SpinnerNumberModel(1000, 1, 10_000_000, 1));
        gbc.gridx = 1; p.add(framesSpin_, gbc); row++;

        // Exposure
        gbc.gridx = 0; gbc.gridy = row;
        p.add(new JLabel("Exposure (ms):"), gbc);
        expSpin_ = new JSpinner(new SpinnerNumberModel(30, 1, 10000, 1));
        gbc.gridx = 1; p.add(expSpin_, gbc); row++;

        // Mag
        gbc.gridx = 0; gbc.gridy = row;
        p.add(new JLabel("Mag (histogram):"), gbc);
        magSpin_ = new JSpinner(new SpinnerNumberModel(5, 1, 50, 1));
        gbc.gridx = 1; p.add(magSpin_, gbc); row++;

        // Update interval
        gbc.gridx = 0; gbc.gridy = row;
        p.add(new JLabel("Update every N frames:"), gbc);
        updSpin_ = new JSpinner(new SpinnerNumberModel(20, 1, 1000, 1));
        gbc.gridx = 1; p.add(updSpin_, gbc); row++;

        // separator
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        p.add(new JSeparator(SwingConstants.HORIZONTAL), gbc); row++; gbc.gridwidth = 1;

        // Processing params
        gbc.gridx = 0; gbc.gridy = row;
        p.add(new JLabel("Background sigma:"), gbc);
        bgSigmaSpin_ = new JSpinner(new SpinnerNumberModel(50.0, 0.1, 500.0, 1.0));
        gbc.gridx = 1; p.add(bgSigmaSpin_, gbc); row++;

        gbc.gridx = 0; gbc.gridy = row;
        p.add(new JLabel("Smoothing sigma:"), gbc);
        smoothSigmaSpin_ = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 10.0, 0.5));
        gbc.gridx = 1; p.add(smoothSigmaSpin_, gbc); row++;

        gbc.gridx = 0; gbc.gridy = row;
        p.add(new JLabel("Min peak distance:"), gbc);
        minPeakSpin_ = new JSpinner(new SpinnerNumberModel(3, 1, 50, 1));
        gbc.gridx = 1; p.add(minPeakSpin_, gbc); row++;

        gbc.gridx = 0; gbc.gridy = row;
        p.add(new JLabel("Peak threshold:"), gbc);
        peakThreshSpin_ = new JSpinner(new SpinnerNumberModel(2.0, 0.0, 1000.0, 0.5));
        gbc.gridx = 1; p.add(peakThreshSpin_, gbc); row++;

        gbc.gridx = 0; gbc.gridy = row;
        p.add(new JLabel("ROI size (odd):"), gbc);
        roiSizeSpin_ = new JSpinner(new SpinnerNumberModel(7, 3, 31, 2));
        gbc.gridx = 1; p.add(roiSizeSpin_, gbc); row++;

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        p.add(new JSeparator(SwingConstants.HORIZONTAL), gbc); row++; gbc.gridwidth = 1;

        // Save stack, output folder
        gbc.gridx = 0; gbc.gridy = row;
        saveStackCB_ = new JCheckBox("Save TIFF stack");
        p.add(saveStackCB_, gbc);

        // Output folder selection
        gbc.gridx = 1;
        JButton outBtn = new JButton("Output folder");
        outField_ = new JTextField();
        outField_.setEditable(false);
        outField_.setColumns(28);
        outField_.setPreferredSize(new Dimension(260, outField_.getPreferredSize().height));
        outField_.setToolTipText("(no folder selected)");
        JPanel outPanel = new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));
        outPanel.add(outBtn);
        outPanel.add(Box.createRigidArea(new Dimension(6,0)));
        outPanel.add(outField_);
        p.add(outPanel, gbc);
        row++;

        outBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(frame_) == JFileChooser.APPROVE_OPTION) {
                String full = fc.getSelectedFile().getAbsolutePath();
                outField_.setText(truncatePath(full, 60));
                outField_.setToolTipText(full);
            }
        });

        // Simulation mode
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        p.add(new JSeparator(SwingConstants.HORIZONTAL), gbc); row++; gbc.gridwidth = 1;

        gbc.gridx = 0; gbc.gridy = row;
        simModeCB_ = new JCheckBox("Simulation mode (load TIFFs from folder)");
        p.add(simModeCB_, gbc);

        gbc.gridx = 1;
        JButton simFolderBtn = new JButton("Sim folder");
        simFolderField_ = new JTextField();
        simFolderField_.setEditable(false);
        simFolderField_.setColumns(28);
        simFolderField_.setPreferredSize(new Dimension(260, simFolderField_.getPreferredSize().height));
        simFolderField_.setToolTipText("(no folder selected)");
        JPanel simPanel = new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));
        simPanel.add(simFolderBtn);
        simPanel.add(Box.createRigidArea(new Dimension(6,0)));
        simPanel.add(simFolderField_);
        p.add(simPanel, gbc);
        row++;

        simFolderBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(frame_) == JFileChooser.APPROVE_OPTION) {
                String full = fc.getSelectedFile().getAbsolutePath();
                simFolderField_.setText(truncatePath(full, 60));
                simFolderField_.setToolTipText(full);
            }
        });

        // Save options: multi-page vs per-frame
        gbc.gridx = 0; gbc.gridy = row;
        saveMultiPageRB_ = new JRadioButton("Save as multi-page TIFF (stream)");
        savePerFrameRB_ = new JRadioButton("Save as per-frame TIFF files");
        ButtonGroup bg = new ButtonGroup();
        bg.add(saveMultiPageRB_); bg.add(savePerFrameRB_);
        saveMultiPageRB_.setSelected(true);
        p.add(saveMultiPageRB_, gbc);
        gbc.gridx = 1;
        p.add(savePerFrameRB_, gbc);
        row++;

        // Live preview option
        gbc.gridx = 0; gbc.gridy = row;
        previewDuringAcqCB_ = new JCheckBox("Show live preview during acquisition");
        previewDuringAcqCB_.setSelected(true);
        p.add(previewDuringAcqCB_, gbc);
        row++;

        // Buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        previewBtn_ = new JButton("Preview (single frame)");
        startBtn_ = new JButton("Start");
        stopBtn_ = new JButton("Stop"); stopBtn_.setEnabled(false);
        clearBtn_ = new JButton("Clear histogram");
        btnPanel.add(previewBtn_); btnPanel.add(startBtn_); btnPanel.add(stopBtn_); btnPanel.add(clearBtn_);

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        p.add(btnPanel, gbc); row++;

        // Add progress panel at bottom
        progressPanel_ = new JPanel(new BorderLayout(8,4));
        progressBar_ = new JProgressBar(0, 100);
        progressBar_.setStringPainted(true);
        etaLabel_ = new JLabel("ETA: -");
        
        queueLabel_ = new JLabel("Queue: 0");
        queueLabel_.setFont(queueLabel_.getFont().deriveFont(10f)); // Make it slightly smaller
        queueLabel_.setForeground(java.awt.Color.GRAY);

        JPanel statusRight = new JPanel(new GridLayout(2, 1));
        statusRight.add(etaLabel_);
        statusRight.add(queueLabel_);

        progressPanel_.add(progressBar_, BorderLayout.CENTER);
        progressPanel_.add(statusRight, BorderLayout.EAST); // Add the combined label panel

        frame_.add(p, BorderLayout.CENTER);
        frame_.add(progressPanel_, BorderLayout.SOUTH);
        
        
        progressPanel_.add(progressBar_, BorderLayout.CENTER);
        progressPanel_.add(etaLabel_, BorderLayout.EAST);

        frame_.add(p, BorderLayout.CENTER);
        frame_.add(progressPanel_, BorderLayout.SOUTH);
        frame_.pack();
        frame_.setLocationRelativeTo(null);

        // wiring
        previewBtn_.addActionListener(e -> {
            AcquisitionParameters params = readParameters();
            if (previewCallback != null) previewCallback.accept(params);
        });

        startBtn_.addActionListener(e -> {
            AcquisitionParameters params = readParameters();
            startBtn_.setEnabled(false);
            stopBtn_.setEnabled(true);
            previewBtn_.setEnabled(false);
            if (startCallback != null) startCallback.accept(params);
        });

        stopBtn_.addActionListener(e -> {
            stopBtn_.setEnabled(false);
            startBtn_.setEnabled(true);
            previewBtn_.setEnabled(true);
            if (stopCallback != null) stopCallback.run();
        });

        clearBtn_.addActionListener(e -> {
            if (clearCallback != null) clearCallback.run();
        });
    }

    public void show() { frame_.setVisible(true); }

    public void setPreviewCallback(Consumer<AcquisitionParameters> cb) { this.previewCallback = cb; }
    public void setStartCallback(Consumer<AcquisitionParameters> cb) { this.startCallback = cb; }
    public void setStopCallback(Runnable cb) { this.stopCallback = cb; }
    public void setClearCallback(Runnable cb) { this.clearCallback = cb; }

    private AcquisitionParameters readParameters() {
        AcquisitionParameters p = new AcquisitionParameters();
        p.frames = ((Number) framesSpin_.getValue()).intValue();
        p.exposureMs = ((Number) expSpin_.getValue()).intValue();
        p.mag = ((Number) magSpin_.getValue()).intValue();
        p.updateInterval = ((Number) updSpin_.getValue()).intValue();
        p.backgroundSigma = ((Number) bgSigmaSpin_.getValue()).doubleValue();
        p.smoothingSigma = ((Number) smoothSigmaSpin_.getValue()).doubleValue();
        p.minPeakDistance = ((Number) minPeakSpin_.getValue()).intValue();
        p.peakThreshold = ((Number) peakThreshSpin_.getValue()).doubleValue();
        p.roiSize = ((Number) roiSizeSpin_.getValue()).intValue();
        if ((p.roiSize % 2) == 0) p.roiSize++;
        p.saveStack = saveStackCB_.isSelected();

        String outText = outField_.getToolTipText();
        p.outDir = (outText == null || outText.equals("(no folder selected)")) ? null : outText;

        p.simulationMode = simModeCB_.isSelected();
        String simText = simFolderField_.getToolTipText();
        p.simFolder = (simText == null || simText.equals("(no folder selected)")) ? null : simText;

        p.saveMultipage = saveMultiPageRB_.isSelected();
        p.saveSingleFiles = savePerFrameRB_.isSelected();
        p.showLivePreview = previewDuringAcqCB_.isSelected();

        return p;
    }

    private static String truncatePath(String path, int maxLen) {
        if (path == null) return "";
        if (path.length() <= maxLen) return path;
        int keep = Math.max(6, maxLen / 3);
        String head = path.substring(0, keep);
        String tail = path.substring(path.length() - keep);
        return head + "..." + tail;
    }

    //  ProgressListener implementation

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
            }
            etaLabel_.setText("ETA: calculating...");
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

    @Override
    public void closeProgress() {
        SwingUtilities.invokeLater(() -> {
            progressBar_.setIndeterminate(false);
            progressBar_.setValue(progressBar_.getMaximum());
            etaLabel_.setText("ETA: done");
        });
    }
    
    @Override
    public void onCameraFinished() {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(frame_, 
                "Camera Acquisition Finished!\nProcessing remaining frames...", 
                "Acquisition Status", 
                JOptionPane.INFORMATION_MESSAGE);
        });
    }

    @Override
    public void onQueueStatus(int pendingCount) {
        SwingUtilities.invokeLater(() -> {
            queueLabel_.setText("Queue: " + pendingCount);
            // Optional: Turn red if queue is getting full (e.g., > 80)
            if (pendingCount > 80) queueLabel_.setForeground(java.awt.Color.RED);
            else queueLabel_.setForeground(java.awt.Color.GRAY);
        });
    }
}
