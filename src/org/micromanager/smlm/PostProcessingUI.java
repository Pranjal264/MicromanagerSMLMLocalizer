package org.micromanager.smlm;

import ij.ImagePlus;
import ij.gui.Plot;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.process.ImageProcessor;
import java.util.ArrayList;
import java.util.List;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Locale;
import java.util.Properties;

/**
 * Post-processing window for 3D (astigmatism) work. Nothing here runs during acquisition.
 *
 *   A. Z calibration       build from a localized bead z-stack, or load a saved calibration / lookup table
 *   B. Apply and render    compute z for a localization CSV, write the 3D CSV, render colour image + z-stack
 */
public class PostProcessingUI {

    private static final String NO_FILE = "(no file selected)";

    private JFrame frame_;

    // ---- A: calibration
    private JTextField beadCsvField_;
    private JLabel beadInfoLabel_;
    private JSpinner zStartSpin_, zStepSpin_, fpsSpin_;
    private JSpinner beadRadiusSpin_, presenceSpin_, degreeSpin_, beadPixelSpin_, beadRoiSpin_;
    private JCheckBox autoRangeCB_;
    private JSpinner fitZMinSpin_, fitZMaxSpin_;
    private JButton buildBtn_, loadBtn_, saveBtn_;
    private JLabel calLabel_;

    // ---- B: apply and render
    private JTextField locCsvField_;
    private JLabel locInfoLabel_;
    private JSpinner zScaleSpin_, widthFactorSpin_;
    private JCheckBox dropRejectedCB_;
    private JSpinner pixelSpin_, sigmaSpin_, zBinSpin_;
    private JCheckBox autoZCB_;
    private JSpinner zMinSpin_, zMaxSpin_;
    private JCheckBox stackCB_, saveCsvCB_, saveImagesCB_, renderWithoutCalibrationCB_;
    private JButton runBtn_;

    private JProgressBar busyBar_;
    private JLabel statusLabel_;

    private File beadCsv_, locCsv_;
    private String defaultFolder_ = null;
    private volatile ZCalibration cal_ = null;

    public PostProcessingUI() { createGui(); }

    /** Folder where the file choosers start (normally the acquisition output folder). */
    public void setDefaultFolder(String folder) { this.defaultFolder_ = folder; }

    public void show() {
        frame_.setVisible(true);
        frame_.toFront();
    }

    // =====================================================================
    //  GUI
    // =====================================================================

    private void createGui() {
        frame_ = new JFrame("SMLM Localizer - 3D calibration & rendering");
        frame_.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame_.setLayout(new BorderLayout(0, 4));

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));
        center.add(createCalibrationPanel());
        center.add(Box.createVerticalStrut(6));
        center.add(createRenderPanel());

        busyBar_ = new JProgressBar();
        busyBar_.setPreferredSize(new Dimension(100, 8));
        statusLabel_ = new JLabel("Ready.");
        statusLabel_.setFont(statusLabel_.getFont().deriveFont(Font.ITALIC));
        JPanel south = new JPanel(new BorderLayout(0, 3));
        south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        south.add(busyBar_, BorderLayout.NORTH);
        south.add(statusLabel_, BorderLayout.CENTER);

        frame_.add(center, BorderLayout.CENTER);
        frame_.add(south, BorderLayout.SOUTH);
        updateEnabledStates();
        frame_.pack();
        frame_.setResizable(false);
        frame_.setLocationRelativeTo(null);
    }

    private JPanel createCalibrationPanel() {
        JPanel p = titledGridPanel("A. Z calibration");
        int r = 0;

        beadCsvField_ = pathField();
        JButton beadBrowse = new JButton("Browse...");
        beadBrowse.addActionListener(e -> {
            File f = chooseFile("Bead localization CSV or bead z-stack TIFF", false);
            if (f != null) { beadCsv_ = f; beadCsvField_.setText(f.getAbsolutePath()); beadCsvField_.setToolTipText(f.getAbsolutePath()); showInfo(beadInfoLabel_, f); }
        });
        addRow(p, r++, "Bead CSV / stack TIFF:", pathPanel(beadCsvField_, beadBrowse));
        beadInfoLabel_ = infoLabel();
        addRow(p, r++, "", beadInfoLabel_);

        beadPixelSpin_ = doubleSpinner(100.0, 1.0, 10000.0, 1.0);
        beadPixelSpin_.setToolTipText("Sample-plane pixel size used to convert TIFF-localized positions and widths from pixels to nm");
        addRow(p, r++, "Bead stack pixel size (nm):", beadPixelSpin_);

        beadRoiSpin_ = intSpinner(9, 3, 31, 2);
        beadRoiSpin_.setToolTipText("Odd-sized localization ROI used for each bead-stack TIFF plane. Use the same ROI as for experimental localizations.");
        addRow(p, r++, "Bead stack ROI size (odd):", beadRoiSpin_);

        zStartSpin_ = doubleSpinner(0.0, -1e6, 1e6, 10.0);
        zStartSpin_.setToolTipText("Stage z (nm) during the first plane");
        addRow(p, r++, "Z at first plane (nm):", zStartSpin_);
        zStepSpin_ = doubleSpinner(50.0, -1e5, 1e5, 10.0);
        zStepSpin_.setToolTipText("Stage step between planes (nm); negative if the stage moved down");
        addRow(p, r++, "Z step per plane (nm):", zStepSpin_);
        fpsSpin_ = intSpinner(1, 1, 100000, 1);
        fpsSpin_.setToolTipText("Consecutive frames recorded at each z position. z of frame f = start + floor((f-1)/frames) * step");
        addRow(p, r++, "Frames per z step:", fpsSpin_);

        addSeparator(p, r++);

        beadRadiusSpin_ = doubleSpinner(300.0, 10.0, 100000.0, 50.0);
        beadRadiusSpin_.setToolTipText("Localizations within this distance are treated as the same bead");
        addRow(p, r++, "Bead match radius (nm):", beadRadiusSpin_);
        presenceSpin_ = doubleSpinner(50.0, 1.0, 100.0, 5.0);
        presenceSpin_.setToolTipText("A bead must be localized in at least this % of the planes (filters out noise)");
        addRow(p, r++, "Min. presence (% of planes):", presenceSpin_);
        degreeSpin_ = intSpinner(3, 1, 8, 1);
        addRow(p, r++, "Polynomial degree:", degreeSpin_);

        autoRangeCB_ = new JCheckBox("Auto fit range (steep, monotonic part of the curve)", true);
        autoRangeCB_.addActionListener(e -> updateEnabledStates());
        addWide(p, r++, autoRangeCB_);
        fitZMinSpin_ = doubleSpinner(-400.0, -1e6, 1e6, 50.0);
        fitZMaxSpin_ = doubleSpinner(400.0, -1e6, 1e6, 50.0);
        addRow(p, r++, "Fit z from / to (nm):", pair(fitZMinSpin_, fitZMaxSpin_));

        buildBtn_ = new JButton("Build from bead stack");
        buildBtn_.addActionListener(e -> buildCalibration());
        loadBtn_ = new JButton("Load calibration...");
        loadBtn_.setToolTipText("A saved .zcal file, or a lookup-table CSV with columns z_nm, sigmax_nm, sigmay_nm");
        loadBtn_.addActionListener(e -> loadCalibration());
        saveBtn_ = new JButton("Save calibration...");
        saveBtn_.addActionListener(e -> saveCalibration());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btns.add(buildBtn_); btns.add(loadBtn_); btns.add(saveBtn_);
        addWide(p, r++, btns);

        calLabel_ = new JLabel("Active calibration: none");
        calLabel_.setFont(calLabel_.getFont().deriveFont(Font.BOLD));
        addWide(p, r++, calLabel_);
        return p;
    }

    private JPanel createRenderPanel() {
        JPanel p = titledGridPanel("B. Apply calibration & render");
        int r = 0;

        locCsvField_ = pathField();
        JButton locBrowse = new JButton("Browse...");
        locBrowse.addActionListener(e -> {
            File f = chooseFile("Localizations (CSV)", false);
            if (f != null) { locCsv_ = f; locCsvField_.setText(f.getAbsolutePath()); locCsvField_.setToolTipText(f.getAbsolutePath()); showInfo(locInfoLabel_, f); }
        });
        addRow(p, r++, "Localization CSV:", pathPanel(locCsvField_, locBrowse));
        locInfoLabel_ = infoLabel();
        addRow(p, r++, "", locInfoLabel_);

        zScaleSpin_ = doubleSpinner(1.0, 0.1, 5.0, 0.05);
        zScaleSpin_.setToolTipText("Multiplies z. Use for the refractive-index mismatch between immersion medium and sample (typically 0.7 - 0.9 for oil into water-based samples); 1 = off");
        addRow(p, r++, "Z scale factor:", zScaleSpin_);
        widthFactorSpin_ = doubleSpinner(2.0, 0.0, 20.0, 0.5);
        widthFactorSpin_.setToolTipText("Far from focus the ratio folds back onto in-range values. Emitters with sqrt(sx*sy) larger than this factor times the widest calibrated plane are rejected. 0 = off");
        addRow(p, r++, "Reject if width > N x calibrated:", widthFactorSpin_);
        dropRejectedCB_ = new JCheckBox("Discard rejected localizations (outside calibrated range)", true);
        addWide(p, r++, dropRejectedCB_);

        addSeparator(p, r++);

        pixelSpin_ = doubleSpinner(20.0, 1.0, 1000.0, 5.0);
        addRow(p, r++, "Render pixel size (nm):", pixelSpin_);
        sigmaSpin_ = doubleSpinner(20.0, 1.0, 500.0, 5.0);
        sigmaSpin_.setToolTipText("Each localization is drawn as a Gaussian of this width");
        addRow(p, r++, "Render sigma (nm):", sigmaSpin_);
        zBinSpin_ = doubleSpinner(50.0, 1.0, 5000.0, 10.0);
        addRow(p, r++, "Z-stack bin (nm):", zBinSpin_);
        autoZCB_ = new JCheckBox("Auto z range (0.5 - 99.5 percentile)", true);
        autoZCB_.addActionListener(e -> updateEnabledStates());
        addWide(p, r++, autoZCB_);
        zMinSpin_ = doubleSpinner(-400.0, -1e6, 1e6, 50.0);
        zMaxSpin_ = doubleSpinner(400.0, -1e6, 1e6, 50.0);
        addRow(p, r++, "Z range from / to (nm):", pair(zMinSpin_, zMaxSpin_));
        stackCB_ = new JCheckBox("Also build a z-stack of density slices", true);
        addWide(p, r++, stackCB_);

        addSeparator(p, r++);

        renderWithoutCalibrationCB_ = new JCheckBox("Render 2D reconstruction without 3D calibration (z = 0)", false);
        renderWithoutCalibrationCB_.setToolTipText("Allows a 2D density reconstruction when no astigmatic z calibration is available.");
        addWide(p, r++, renderWithoutCalibrationCB_);

        saveCsvCB_ = new JCheckBox("Save CSV (localizations_3D.csv, next to the input)", true);
        addWide(p, r++, saveCsvCB_);
        saveImagesCB_ = new JCheckBox("Save images as TIFF (reconstruction_3D_color.tif, reconstruction_3D_stack.tif)", false);
        addWide(p, r++, saveImagesCB_);

        runBtn_ = new JButton("Compute z and render");
        runBtn_.addActionListener(e -> applyAndRender());
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        b.add(runBtn_);
        addWide(p, r++, b);
        return p;
    }

    // =====================================================================
    //  Actions
    // =====================================================================

    private void buildCalibration() {
        if (beadCsv_ == null) { warn("Select a bead localization CSV or a bead z-stack TIFF first."); return; }
        final File csv = beadCsv_;
        final CalibrationBuilder.Params bp = new CalibrationBuilder.Params();
        bp.zStartNm = num(zStartSpin_); bp.zStepNm = num(zStepSpin_);
        bp.framesPerStep = (int) num(fpsSpin_);
        bp.beadRadiusNm = num(beadRadiusSpin_); bp.minPresence = num(presenceSpin_) / 100.0;
        final int degree = (int) num(degreeSpin_);
        final boolean auto = autoRangeCB_.isSelected();
        final double zLo = num(fitZMinSpin_), zHi = num(fitZMaxSpin_);
        final double beadPixelSizeNm = num(beadPixelSpin_);
        final int beadRoiSize = (int) num(beadRoiSpin_);

        runAsync("Reading bead localizations...", () -> {
            LocalizationTable t;
            String lower = csv.getName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
                status("Localizing bead z-stack TIFF...");
                t = localizeBeadStack(csv, beadPixelSizeNm, beadRoiSize);
            } else {
                t = LocalizationTable.load(csv);
            }
            Properties info = AcquisitionInfo.read(csv);
            status("Finding beads in " + t.n + " localizations...");
            CalibrationCurve curve = CalibrationBuilder.build(t, bp);
            status("Fitting...");
            int calibrationRoi = (lower.endsWith(".tif") || lower.endsWith(".tiff"))
                    ? beadRoiSize
                    : (AcquisitionInfo.roiSize(info) > 0 ? AcquisitionInfo.roiSize(info) : beadRoiSize);
            ZCalibration c = ZCalibration.fit(curve, degree, auto, zLo, zHi,
                    calibrationRoi,
                    AcquisitionInfo.pixelSizeNm(info) > 0 ? AcquisitionInfo.pixelSizeNm(info) : beadPixelSizeNm);
            setCalibration(c, "Calibration built from " + curve.totalBeads + " bead(s), " + curve.size() + " planes.");
        });
    }

    private void loadCalibration() {
        File f = chooseFile("Calibration file (.zcal) or lookup table (.csv)", false);
        if (f == null) return;
        final int degree = (int) num(degreeSpin_);
        final boolean auto = autoRangeCB_.isSelected();
        final double zLo = num(fitZMinSpin_), zHi = num(fitZMaxSpin_);
        runAsync("Loading calibration...", () -> {
            ZCalibration c;
            String how;
            if (f.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                CalibrationCurve curve = CalibrationCurve.fromLutCsv(f);
                c = ZCalibration.fit(curve, degree, auto, zLo, zHi, 0, 0);
                how = "Lookup table refitted with the current degree/range settings.";
            } else {
                c = ZCalibration.load(f);
                how = "Calibration loaded from " + f.getName() + ".";
            }
            setCalibration(c, how);
        });
    }

    private void saveCalibration() {
        final ZCalibration c = cal_;
        if (c == null) { warn("There is no calibration to save yet."); return; }
        JFileChooser fc = new JFileChooser(startDir());
        fc.setDialogTitle("Save calibration");
        fc.setSelectedFile(new File(startDir(), "z_calibration.zcal"));
        if (fc.showSaveDialog(frame_) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".zcal")) f = new File(f.getParentFile(), f.getName() + ".zcal");
        final File target = f;
        runAsync("Saving calibration...", () -> {
            c.save(target);
            String msg = "Saved " + target.getName();
            if (c.curve != null) {
                File lut = new File(target.getParentFile(), target.getName().replaceAll("(?i)\\.zcal$", "") + "_curve.csv");
                c.curve.writeCsv(lut);
                msg += " and lookup table " + lut.getName();
            }
            status(msg);
        });
    }

    private void applyAndRender() {
        final ZCalibration c = cal_;
        final boolean render2D = renderWithoutCalibrationCB_.isSelected();
        if (c == null && !render2D) { warn("Build/load a calibration, or select 2D rendering without calibration."); return; }
        if (locCsv_ == null) { warn("Select the localization CSV to process."); return; }

        final File csv = locCsv_;
        int roi = AcquisitionInfo.roiSize(AcquisitionInfo.read(csv));
        if (!render2D && c != null && c.roiSize > 0 && roi > 0 && roi != c.roiSize) {
            int ans = JOptionPane.showConfirmDialog(frame_,
                    "The calibration was made with ROI " + c.roiSize + " px but these localizations used ROI " + roi
                            + " px.\nPhasor widths depend on the ROI size, so z values will be wrong.\n\nContinue anyway?",
                    "ROI size mismatch", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ans != JOptionPane.YES_OPTION) return;
        }

        final double zScale = num(zScaleSpin_), widthFactor = num(widthFactorSpin_);
        final boolean drop = dropRejectedCB_.isSelected();
        final Renderer3D.Params rp = new Renderer3D.Params();
        rp.pixelNm = num(pixelSpin_); rp.sigmaNm = num(sigmaSpin_); rp.zBinNm = num(zBinSpin_);
        rp.autoZRange = autoZCB_.isSelected(); rp.zMinNm = num(zMinSpin_); rp.zMaxNm = num(zMaxSpin_);
        rp.makeStack = stackCB_.isSelected();
        final boolean saveCsv = saveCsvCB_.isSelected(), saveImgs = saveImagesCB_.isSelected();

        runAsync("Reading localizations...", () -> {
            LocalizationTable t = LocalizationTable.load(csv);
            LocalizationTable outTable;
            StringBuilder msg = new StringBuilder();
            if (render2D) {
                double[] zeroZ = new double[t.n];
                outTable = t.withZ(zeroZ);
                msg.append("2D rendering without z calibration; all z values set to 0.");
                rp.makeStack = false;
            } else {
                status("Computing z for " + t.n + " localizations...");
                ThreeDPipeline.ApplyResult ar = ThreeDPipeline.apply(t, c, zScale, widthFactor, drop);
                if (ar.kept() == 0) throw new IllegalStateException("No localization has a valid z. Check calibration, ROI size and optics.");
                outTable = ar.table;
                msg.append(ar.kept()).append(" of ").append(ar.total).append(" localizations got a z");
                if (ar.outOfRange > 0) msg.append("; ").append(ar.outOfRange).append(" outside the calibrated range");
                if (ar.tooWide > 0) msg.append("; ").append(ar.tooWide).append(" rejected as too wide");
                msg.append(".");
            }
            File dir = csv.getParentFile();

            if (saveCsv) {
                File out = new File(dir, render2D ? "localizations_2D.csv" : "localizations_3D.csv");
                if (render2D) t.writeCsv(out); else outTable.writeCsv(out);
                msg.append(" Saved ").append(out.getName()).append(".");
            }
            status("Rendering...");
            Renderer3D.Result res = Renderer3D.render(outTable, rp);
            if (saveImgs) {
                new FileSaver(res.color).saveAsTiff(new File(dir, render2D ? "reconstruction_2D.tif" : "reconstruction_3D_color.tif").getAbsolutePath());
                if (res.stack != null) new FileSaver(res.stack).saveAsTiffStack(new File(dir, "reconstruction_3D_stack.tif").getAbsolutePath());
                msg.append(" Images saved.");
            }
            final ImagePlus color = res.color, stack = res.stack;
            SwingUtilities.invokeLater(() -> { color.show(); if (stack != null) stack.show(); });
            msg.append(String.format(Locale.ROOT, " Rendered %d points, z %.0f to %.0f nm (blue to red).", res.used, res.zLo, res.zHi));
            status(msg.toString());
        });
    }


    /** Localize every slice of a bead z-stack TIFF and convert pixel coordinates/widths to nm. */
    private static LocalizationTable localizeBeadStack(File file, double pixelSizeNm, int roiSize) throws Exception {
        if (roiSize < 3 || roiSize > 31 || roiSize % 2 == 0) {
            throw new IllegalArgumentException("Bead ROI size must be an odd number from 3 to 31 pixels.");
        }
        ImagePlus imp = new Opener().openImage(file.getAbsolutePath());
        if (imp == null) throw new IllegalArgumentException("Could not open bead TIFF: " + file.getAbsolutePath());
        int slices = imp.getStackSize();
        if (slices < 3) throw new IllegalArgumentException("Bead calibration TIFF must contain at least 3 z planes.");
        List<Integer> frames = new ArrayList<>();
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>(), sxs = new ArrayList<>(), sys = new ArrayList<>(), amps = new ArrayList<>();
        LocalizationProcessor processor = new LocalizationProcessor();
        for (int z = 1; z <= slices; z++) {
            ImageProcessor ip = imp.getStack().getProcessor(z).convertToFloatProcessor();
            int w = ip.getWidth(), h = ip.getHeight();
            double[][] image = new double[h][w];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) image[y][x] = ip.getf(x, y);
            List<Localization> locs = processor.processImageForAcquisition(image, z, 3.0, 1.0, 3, 8.0, roiSize);
            for (Localization loc : locs) {
                if (!(loc.sigmaX > 0 && loc.sigmaY > 0 && Double.isFinite(loc.sigmaX) && Double.isFinite(loc.sigmaY))) continue;
                frames.add(z); xs.add(loc.x * pixelSizeNm); ys.add(loc.y * pixelSizeNm);
                sxs.add(loc.sigmaX * pixelSizeNm); sys.add(loc.sigmaY * pixelSizeNm); amps.add(loc.amplitude);
            }
        }
        if (frames.size() < 10) throw new IllegalArgumentException("Too few bead localizations found in TIFF. Check detection threshold, focus, and pixel size.");
        int n = frames.size(); int[] f = new int[n]; double[] x = new double[n], y = new double[n], sx = new double[n], sy = new double[n], a = new double[n];
        String[] names = new String[n];
        for (int i = 0; i < n; i++) { f[i] = frames.get(i); x[i] = xs.get(i); y[i] = ys.get(i); sx[i] = sxs.get(i); sy[i] = sys.get(i); a[i] = amps.get(i); names[i] = ""; }
        return new LocalizationTable(f, x, y, null, sx, sy, a, names);
    }

    private void setCalibration(ZCalibration c, String message) {
        cal_ = c;
        SwingUtilities.invokeLater(() -> {
            calLabel_.setText("Active calibration: " + c.summary());
            updateEnabledStates();
            showPlots(c);
        });
        status(message + " " + c.summary() + ".");
    }

    /** Calibration curve (what is fitted) and PSF widths versus z (to check the astigmatism). */
    private static void showPlots(ZCalibration c) {
        CalibrationCurve cv = c.curve;
        if (cv == null || cv.size() == 0) return;
        double zMinAll = Double.POSITIVE_INFINITY, zMaxAll = Double.NEGATIVE_INFINITY;
        double lMinAll = Double.POSITIVE_INFINITY, lMaxAll = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < cv.size(); i++) {
            zMinAll = Math.min(zMinAll, cv.z[i]); zMaxAll = Math.max(zMaxAll, cv.z[i]);
            lMinAll = Math.min(lMinAll, cv.logRatio[i]); lMaxAll = Math.max(lMaxAll, cv.logRatio[i]);
        }
        double zPad = 0.05 * Math.max(1, zMaxAll - zMinAll), lPad = 0.05 * Math.max(1e-6, lMaxAll - lMinAll);

        Plot p1 = new Plot("Calibration branch (gray: unused, blue: fitted range)",
                "ln(sigma x / sigma y)", "z (nm)");
        p1.setLimits(lMinAll - lPad, lMaxAll + lPad, zMinAll - zPad, zMaxAll + zPad);
        p1.setColor(Color.GRAY);
        p1.addPoints(cv.logRatio, cv.z, Plot.CIRCLE);
        if (c.fitFirst >= 0 && c.fitLast < cv.size() && c.fitLast >= c.fitFirst) {
            int m = c.fitLast - c.fitFirst + 1;
            double[] xs = new double[m], ys = new double[m];
            for (int i = 0; i < m; i++) { xs[i] = cv.logRatio[c.fitFirst + i]; ys[i] = cv.z[c.fitFirst + i]; }
            p1.setColor(Color.BLUE);
            p1.addPoints(xs, ys, Plot.CIRCLE);
        }
        p1.show();

        double sMax = 0;
        for (int i = 0; i < cv.size(); i++) sMax = Math.max(sMax, Math.max(cv.sigmaX[i], cv.sigmaY[i]));
        Plot p2 = new Plot("PSF widths vs z (blue: sigma x, red: sigma y)", "z (nm)", "sigma (nm)");
        p2.setLimits(zMinAll - zPad, zMaxAll + zPad, 0, sMax * 1.1);
        p2.setColor(Color.BLUE);
        p2.addPoints(cv.z, cv.sigmaX, Plot.CIRCLE);
        p2.addPoints(cv.z, cv.sigmaX, Plot.LINE);
        p2.setColor(Color.RED);
        p2.addPoints(cv.z, cv.sigmaY, Plot.CIRCLE);
        p2.addPoints(cv.z, cv.sigmaY, Plot.LINE);
        p2.show();
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private interface Task { void run() throws Exception; }

    private void runAsync(String initialStatus, Task task) {
        setBusy(true);
        status(initialStatus);
        Thread t = new Thread(() -> {
            try {
                task.run();
            } catch (Throwable ex) {
                ex.printStackTrace();
                final String m = (ex.getMessage() != null) ? ex.getMessage() : ex.toString();
                status("Error: " + m);
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(frame_, m, "3D tools", JOptionPane.ERROR_MESSAGE));
            } finally {
                SwingUtilities.invokeLater(() -> setBusy(false));
            }
        }, "SMLM-3D-Worker");
        t.setDaemon(true);
        t.start();
    }

    private void setBusy(boolean busy) {
        busyBar_.setIndeterminate(busy);
        buildBtn_.setEnabled(!busy);
        loadBtn_.setEnabled(!busy);
        runBtn_.setEnabled(!busy);
        saveBtn_.setEnabled(!busy && cal_ != null);
    }

    private void status(String s) {
        SwingUtilities.invokeLater(() -> statusLabel_.setText("<html>" + s.replace("&", "&amp;").replace("<", "&lt;") + "</html>"));
    }

    private void updateEnabledStates() {
        boolean manualFit = !autoRangeCB_.isSelected();
        fitZMinSpin_.setEnabled(manualFit); fitZMaxSpin_.setEnabled(manualFit);
        boolean manualZ = !autoZCB_.isSelected();
        zMinSpin_.setEnabled(manualZ); zMaxSpin_.setEnabled(manualZ);
        saveBtn_.setEnabled(cal_ != null);
    }

    private void showInfo(JLabel label, File csv) {
        label.setText(AcquisitionInfo.describe(AcquisitionInfo.read(csv)));
        if (defaultFolder_ == null && csv.getParentFile() != null) defaultFolder_ = csv.getParentFile().getAbsolutePath();
    }

    private File startDir() {
        return defaultFolder_ != null ? new File(defaultFolder_) : null;
    }

    private File chooseFile(String title, boolean save) {
        JFileChooser fc = new JFileChooser(startDir());
        fc.setDialogTitle(title);
        int rv = save ? fc.showSaveDialog(frame_) : fc.showOpenDialog(frame_);
        return rv == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile() : null;
    }

    private void warn(String msg) {
        JOptionPane.showMessageDialog(frame_, msg, "3D tools", JOptionPane.WARNING_MESSAGE);
    }

    private static double num(JSpinner s) { return ((Number) s.getValue()).doubleValue(); }

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
        g.fill = (c instanceof JPanel || c instanceof JLabel) ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        p.add(c, g);
    }

    private static void addWide(JPanel p, int row, JComponent c) {
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 6, 2, 6);
        g.gridy = row; g.gridx = 0; g.gridwidth = 2; g.anchor = GridBagConstraints.WEST;
        p.add(c, g);
    }

    private static void addSeparator(JPanel p, int row) {
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.gridy = row; g.gridx = 0; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL;
        p.add(new JSeparator(SwingConstants.HORIZONTAL), g);
    }

    private static JSpinner intSpinner(int v, int min, int max, int step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(v, min, max, step));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(8);
        return s;
    }

    private static JSpinner doubleSpinner(double v, double min, double max, double step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(v, min, max, step));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(8);
        return s;
    }

    private static JPanel pair(JComponent a, JComponent b) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.add(a); p.add(b);
        return p;
    }

    private static JTextField pathField() {
        JTextField f = new JTextField(NO_FILE, 30);
        f.setEditable(false);
        return f;
    }

    private static JLabel infoLabel() {
        JLabel l = new JLabel(" ");
        l.setFont(l.getFont().deriveFont(10f));
        l.setForeground(Color.GRAY);
        return l;
    }

    private static JPanel pathPanel(JTextField field, JButton btn) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.add(field, BorderLayout.CENTER);
        p.add(btn, BorderLayout.EAST);
        return p;
    }
}
