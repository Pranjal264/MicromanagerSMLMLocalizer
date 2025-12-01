package org.micromanager.smlm;

import ij.ImagePlus;
import ij.io.FileSaver;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import ij.ImageStack;
import ij.plugin.filter.GaussianBlur;
import ij.gui.OvalRoi;
import ij.gui.Overlay;

import org.micromanager.Studio;

import javax.swing.*;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.io.StringWriter;

import org.apache.commons.math3.complex.Complex;

/**
 * AcquisitionController - handles preview and acquisition loop.
 * - Peak detection on processed image (bg-subtracted + smoothed)
 * - Subpixel phasor localization on raw ROI
 * - save multipage TIFFs using ImageJ ImageStack or single TIFF images
 */
public class AcquisitionController {

    private final Studio studio;
    private final LocalizationProcessor processor;
    private final LocalizationAccumulator accumulator;

    private volatile boolean stopRequested = false;
    private Thread acqThread = null;

    private ImagePlus livePreviewImp = null;
    private final Object livePreviewLock = new Object(); // lock for preview handling

    private volatile ProgressListener progressListener = null;

    // timing / ETA smoothing
    private long acqStartTimeNs = 0L;
    private double avgFrameMs = 0.0;
    private final double ALPHA = 0.12; // EWMA smoothing for per-frame time

    public AcquisitionController(Studio studio,
                                 LocalizationProcessor processor,
                                 LocalizationAccumulator accumulator) {
        this.studio = studio;
        this.processor = processor;
        this.accumulator = accumulator;
    }

    public void setProgressListener(ProgressListener l) {
        this.progressListener = l;
    }

    public void preview(AcquisitionParameters params) {
        new Thread(() -> {
            try {
                double[][] image;
                int w,h;

                if (params.simulationMode) {
                    if (params.simFolder == null) { showError("Simulation folder not set"); return; }
                    String[] list = (processor != null) ? processor.getTiffFileList(params.simFolder) : getTiffFileList(params.simFolder);
                    if (list.length == 0) { showError("No TIFF files found in simulation folder: " + params.simFolder); return; }
                    image = (processor != null) ? processor.loadSimImageFromPath(list[0]) : loadSimImageFromPath(list[0]);
                    h = image.length; w = image[0].length;
                } else {
                    if (studio == null) { showError("Studio not available for live preview"); return; }
                    studio.core().snapImage();
                    Object pix = studio.core().getImage();
                    w = (int) studio.core().getImageWidth();
                    h = (int) studio.core().getImageHeight();
                    image = toDoubleArray(pix, w, h);
                }

                // detect peaks on processed image
                ProcessedResult pr = processForPeaks(image, params.backgroundSigma, params.smoothingSigma,
                        params.minPeakDistance, params.peakThreshold, params.roiSize);

                // show RAW image in preview with overlays
                FloatProcessor rawDisplay = createDisplayFromRaw(image);
                ImagePlus previewImp = new ImagePlus("Preview", rawDisplay);

                Overlay ov = new Overlay();
                int r = Math.max(1, params.roiSize / 2);
                for (int[] pxy : pr.peaks) {
                    int px = pxy[0], py = pxy[1];
                    OvalRoi or = new OvalRoi(px - r, py - r, 2 * r + 1, 2 * r + 1);
                    or.setStrokeColor(java.awt.Color.RED);
                    ov.add(or);
                }
                previewImp.setOverlay(ov);
                SwingUtilities.invokeLater(previewImp::show);

            } catch (Exception ex) {
                showError("Preview error: " + ex.getMessage());
            }
        }, "SMLM-Preview-Thread").start();
    }

    public void startAcquisition(AcquisitionParameters params) {
        stopRequested = false;
        acqThread = new Thread(() -> runAcquisition(params), "SMLM-Acq-Thread");
        acqThread.setDaemon(true);
        acqThread.start();
    }

    public void requestStop() {
        stopRequested = true;
        // attempt to close preview promptly when the user clicks Stop
        closeLivePreview();
        if (acqThread != null) {
            try { acqThread.join(200); } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Main acquisition loop.
     */
    private void runAcquisition(AcquisitionParameters params) {
        int frames = params.frames;
        int exposureMs = params.exposureMs;
        int mag = params.mag;
        int updateInterval = params.updateInterval;
        boolean saveStack = params.saveStack;
        String outDir = params.outDir;
        boolean wantMultipage = params.saveMultipage;
        boolean wantPerFrame = params.saveSingleFiles;
        boolean previewDuring = params.showLivePreview;

        accumulator.reset();

        ImageStack stack = null; // for multipage TIFF fallback
        boolean stackingEnabled = false;

        try {
            if (studio != null) {
                studio.core().setExposure(exposureMs);
            }

            if (saveStack && outDir != null) {
                File d = new File(outDir);
                if (!d.exists() && !d.mkdirs()) {
                    showError("Failed to create output directory: " + outDir);
                    return;
                }
            }

            List<LocalizationCsvRow> csvRows = new ArrayList<>();

            int processed = 0;
            int camW = -1, camH = -1;

            String[] simFiles = null;
            if (params.simulationMode) {
                simFiles = (processor != null) ? processor.getTiffFileList(params.simFolder) : getTiffFileList(params.simFolder);
                if (simFiles.length == 0) {
                    showError("No TIFFs in simulation folder");
                    return;
                }
            }

            // prepare ImageStack if user wants multipage TIFF (plain ImageJ stack, no OME)
            if (saveStack && outDir != null && wantMultipage && !wantPerFrame) {
                // We'll create the stack after first frame size is known (camW/camH)
                stackingEnabled = true;
            }

            // progress listener init
            if (progressListener != null && frames > 0) progressListener.initProgress(frames);
            acqStartTimeNs = System.nanoTime();
            avgFrameMs = 0.0;

            for (int f = 0; f < frames && !stopRequested; f++) {
                double[][] image;
                int w, h;
                String savedFilename = "";

                if (simFiles != null) {
                    String path = simFiles[f % simFiles.length];
                    try {
                        image = (processor != null) ? processor.loadSimImageFromPath(path) : loadSimImageFromPath(path);
                    } catch (Exception ex) {
                        showError("Failed to load simulation image '" + path + "': " + ex.getMessage());
                        break;
                    }
                    h = image.length; w = image[0].length;
                } else {
                    if (studio == null) { showError("Studio not available"); break; }
                    studio.core().snapImage();
                    Object pix = studio.core().getImage();
                    w = (int) studio.core().getImageWidth();
                    h = (int) studio.core().getImageHeight();
                    image = toDoubleArray(pix, w, h);
                }

                // initialize stack if needed now that we know cam size
                if (camW == -1) {
                    camW = w; camH = h;
                    if (stackingEnabled) {
                        stack = new ImageStack(camW, camH);
                    }
                }

                // 1) detect peaks on processed image
                ProcessedResult pr = processForPeaks(image, params.backgroundSigma,
                        params.smoothingSigma, params.minPeakDistance, params.peakThreshold, params.roiSize);
                List<int[]> peaks = pr.peaks; // {x,y}

                // 2) save frame (prefer ImageJ stack for multipage)
                if (saveStack && outDir != null) {
                    if (stack != null) {
                        // convert to 16-bit short per-frame scaling (same per-frame normalization you used)
                        double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
                        for (int yy = 0; yy < h; yy++) for (int xx = 0; xx < w; xx++) {
                            double v = image[yy][xx];
                            if (v < mn) mn = v;
                            if (v > mx) mx = v;
                        }
                        double range = mx - mn;
                        if (range <= 0) range = 1.0;

                        short[] shortPix = new short[w * h];
                        int idx = 0;
                        for (int yy = 0; yy < h; yy++) {
                            for (int xx = 0; xx < w; xx++) {
                                int v16 = (int) Math.round((image[yy][xx] - mn) / range * 65535.0);
                                if (v16 < 0) v16 = 0;
                                if (v16 > 65535) v16 = 65535;
                                shortPix[idx++] = (short) (v16 & 0xffff);
                            }
                        }
                        ShortProcessor sp = new ShortProcessor(w, h, shortPix, null);
                        stack.addSlice(String.format("f%06d", f), sp);
                        savedFilename = "frames_stack.tif"; // will be written at the end
                    } else {
                        // per-frame TIFF
                        String fname = String.format("frame_%06d.tif", f);
                        FloatProcessor fp = new FloatProcessor(w, h);
                        for (int yy = 0; yy < h; yy++) for (int xx = 0; xx < w; xx++) fp.setf(xx, yy, (float) image[yy][xx]);
                        ImagePlus im = new ImagePlus(String.format("f%06d", f), fp);
                        FileSaver fs = new FileSaver(im);
                        boolean ok = fs.saveAsTiff(new File(outDir, fname).getAbsolutePath());
                        if (!ok) showError("Failed to save frame " + f + " to disk (per-frame)");
                        else savedFilename = fname;
                    }
                }

                // 3) localize on raw image ROI using phasor method
                List<Localization> locs = localizePeaksOnRaw(image, peaks, params.roiSize, f + 1);

                // 4) accumulate
                accumulator.initIfNeeded(camW, camH, mag);
                accumulator.addLocalizations(locs);

                for (Localization L : locs) {
                    csvRows.add(new LocalizationCsvRow(L.frame, L.amplitude, L.x, L.y, savedFilename));
                }

                // 5) live preview: raw + overlays
                if (previewDuring) {
                    final FloatProcessor rawDisplay = createDisplayFromRaw(image);
                    final List<int[]> peaksCopy = new ArrayList<>(peaks);

                    // Show/update the ImagePlus (creates a new window if previous was closed)
                    showOrUpdateLivePreview(rawDisplay);

                    // Update overlays on EDT (queued after the above show/update)
                    SwingUtilities.invokeLater(() -> {
                        try {
                            // If livePreviewImp was recreated above it will be non-null and have a window.
                            if (livePreviewImp == null) return;
                            Overlay ov = new Overlay();
                            int r = Math.max(1, params.roiSize / 2);
                            for (int[] pxy : peaksCopy) {
                                int px = pxy[0], py = pxy[1];
                                OvalRoi or = new OvalRoi(px - r, py - r, 2 * r + 1, 2 * r + 1);
                                or.setStrokeColor(java.awt.Color.RED);
                                ov.add(or);
                            }
                            livePreviewImp.setOverlay(ov);
                            try { livePreviewImp.updateAndDraw(); } catch (Exception ignore) {}
                        } catch (Throwable t) {
                            if (studio != null) studio.logs().showError("Overlay update error: " + t.getMessage());
                        }
                    });
                }

                // bookkeeping & progress
                processed++;

                // update accumulator display occasionally
                if ((processed % updateInterval) == 0 || stopRequested) accumulator.updateDisplay();

                // update ETA / progress
                updateTimingAndNotifyProgress(processed, frames);

            } // frames loop

            // finalize stack writer (ImageJ multipage TIFF)
            if (stack != null && outDir != null) {
                ImagePlus sImp = new ImagePlus("stack", stack);
                FileSaver fs = new FileSaver(sImp);
                String stackPath = new File(outDir, "frames_stack.tif").getAbsolutePath();
                boolean ok = fs.saveAsTiffStack(stackPath);
                if (!ok) studio.logs().showError("ImageJ TIFF stack save failed: " + stackPath);
                else studio.logs().showMessage("Saved TIFF stack via ImageJ: " + stackPath);
            }

            // write CSV
            if (outDir != null) {
                File csvFile = new File(outDir, "localizations.csv");
                try (PrintWriter pw = new PrintWriter(new FileWriter(csvFile))) {
                    pw.println("Frame,Amplitude,X,Y");
                    for (LocalizationCsvRow row : csvRows) {
                        pw.printf("%d,%.6f,%.6f,%.6f%n",
                                row.frame, row.amp, row.x, row.y);
                    }
                    if (studio != null) studio.logs().showMessage("Saved localizations CSV to " + csvFile.getAbsolutePath());
                } catch (Exception ex) {
                    showError("Failed to write CSV: " + ex.getMessage());
                }
            }

            if (studio != null) studio.alerts().postAlert("Acquisition finished", this.getClass(), "Localizations: " + csvRows.size());

        } catch (Exception ex) {
            showError("Frame error: " + ex.getMessage());
        } finally {
            // ensure progress UI closed
            if (progressListener != null) progressListener.closeProgress();
            // ensure preview closed / cleared
            closeLivePreview();
            stopRequested = false;
        }
    }

    private void updateTimingAndNotifyProgress(int processed, int frames) {
        long now = System.nanoTime();
        if (acqStartTimeNs == 0L) acqStartTimeNs = now;
        double elapsedMs = (now - acqStartTimeNs) / 1e6;
        double instFrameMs = (processed > 0) ? (elapsedMs / processed) : 0.0;
        if (avgFrameMs == 0.0) avgFrameMs = instFrameMs;
        else avgFrameMs = ALPHA * instFrameMs + (1.0 - ALPHA) * avgFrameMs;

        long remaining = Math.max(0, frames - processed);
        long etaMs = Math.round(remaining * avgFrameMs);
        String etaStr = formatMillis(etaMs);

        if (progressListener != null) progressListener.updateProgress(processed, frames, etaStr);
    }

    private String formatMillis(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, sec);
        if (m > 0) return String.format("%dm %ds", m, sec);
        return String.format("%ds", sec);
    }

    // Processing & Localization

    private static class ProcessedResult {
        public final FloatProcessor display; // processed display if needed
        public final List<int[]> peaks;      // candidate peaks {x,y}
        public ProcessedResult(FloatProcessor d, List<int[]> p) { display = d; peaks = p; }
    }

    /**
     * Detection on processed image (bg-subtracted + smoothed).
     */
    private ProcessedResult processForPeaks(double[][] image, double bgSigma, double smoothSigma,
                                            int minPeakDistance, double peakThreshold, int roiSize) {
        int h = image.length;
        int w = (h > 0) ? image[0].length : 0;

        // 1) min-max normalize to 0..255
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double v = image[y][x];
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double range = max - min;
        double[][] norm = new double[h][w];
        if (range == 0) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) norm[y][x] = image[y][x];
        } else {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) norm[y][x] = (image[y][x] - min) / range * 255.0;
        }

        // 2) background blur
        FloatProcessor fpBg = new FloatProcessor(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) fpBg.setf(x, y, (float) norm[y][x]);
        GaussianBlur gb = new GaussianBlur();
        gb.blurFloat(fpBg, (float) bgSigma, (float) bgSigma, 0.02f);
        double[][] background = new double[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) background[y][x] = fpBg.getf(x, y);

        // 3) bg-subtract and small smoothing
        double[][] imgBg = new double[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) imgBg[y][x] = norm[y][x] - background[y][x];

        FloatProcessor fpSm = new FloatProcessor(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) fpSm.setf(x, y, (float) imgBg[y][x]);
        gb.blurFloat(fpSm, (float) smoothSigma, (float) smoothSigma, 0.02f);
        double[][] imgSm = new double[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) imgSm[y][x] = fpSm.getf(x, y);

        // 4) detect integer peaks on imgSm: returns list of {x,y}
        List<int[]> peaks = new ArrayList<>();
        int radius = Math.max(1, minPeakDistance);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double v = imgSm[y][x];
                if (v < peakThreshold) continue;
                boolean isMax = true;
                for (int yy = Math.max(0, y - radius); yy <= Math.min(h - 1, y + radius) && isMax; yy++) {
                    for (int xx = Math.max(0, x - radius); xx <= Math.min(w - 1, x + radius); xx++) {
                        if (yy == y && xx == x) continue;
                        if (imgSm[yy][xx] >= v) { isMax = false; break; }
                    }
                }
                if (isMax) peaks.add(new int[]{x, y}); // store as {x,y}
            }
        }

        // prepare processed display (not used in preview here)
        FloatProcessor displayFP = new FloatProcessor(w, h);
        double dmin = Double.POSITIVE_INFINITY, dmax = Double.NEGATIVE_INFINITY;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double vv = imgSm[y][x];
            if (vv < dmin) dmin = vv;
            if (vv > dmax) dmax = vv;
        }
        double drange = Math.max(1e-6, dmax - dmin);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float val = (float) ((imgSm[y][x] - dmin) / drange * 255.0);
            displayFP.setf(x, y, val);
        }

        return new ProcessedResult(displayFP, peaks);
    }

    /**
     * Phasor localization on raw image ROI.
     */
    private List<Localization> localizePeaksOnRaw(double[][] image, List<int[]> peaks, int roiSize, int frameNumber) {
        List<Localization> locs = new ArrayList<>();
        int half = roiSize / 2;
        int H = image.length;
        int W = (H>0) ? image[0].length : 0;

        for (int pi = 0; pi < peaks.size(); pi++) {
            int[] p = peaks.get(pi);
            int px = p[0]; int py = p[1];

            if (py - half < 0 || (py + half + 1) > H || px - half < 0 || (px + half + 1) > W) continue;

            double[][] sub = new double[roiSize][roiSize];
            double maxVal = Double.NEGATIVE_INFINITY;
            double sum = 0.0;
            for (int ry = 0; ry < roiSize; ry++) {
                for (int rx = 0; rx < roiSize; rx++) {
                    double v = image[py - half + ry][px - half + rx];
                    sub[ry][rx] = v;
                    sum += v;
                    if (v > maxVal) maxVal = v;
                }
            }

            Complex[][] fft = Fft2D.fft2(sub);

            Complex cx = fft[0][1];
            Complex cy = fft[1][0];

            double angX = Math.atan2(cx.getImaginary(), cx.getReal());
            if (angX > 0) angX -= 2.0 * Math.PI;
            double angY = Math.atan2(cy.getImaginary(), cy.getReal());
            if (angY > 0) angY -= 2.0 * Math.PI;

            double posX = Math.abs(angX) / (2.0 * Math.PI / roiSize);
            double posY = Math.abs(angY) / (2.0 * Math.PI / roiSize);

            double globalX = px - half + posX;
            double globalY = py - half + posY;

            locs.add(new Localization(frameNumber, maxVal, globalX, globalY));
        }

        return locs;
    }

    // helper methods 

    /**
     * Normalize raw image for display (min..max -> 0..255).
     */
    private static FloatProcessor createDisplayFromRaw(double[][] image) {
        int h = image.length;
        int w = (h > 0) ? image[0].length : 0;
        FloatProcessor fp = new FloatProcessor(w, h);

        double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double v = image[y][x];
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        double range = mx - mn;
        if (range <= 0) range = 1.0;

        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float val = (float) ((image[y][x] - mn) / range * 255.0);
            fp.setf(x, y, val);
        }
        return fp;
    }

    /**
     * Show or update the live preview ImagePlus on the EDT.
     */
    private void showOrUpdateLivePreview(final FloatProcessor rawDisplay) {
        synchronized (livePreviewLock) {
            SwingUtilities.invokeLater(() -> {
                try {
                    // if no ImagePlus or its window was closed, create+show a fresh one
                    if (livePreviewImp == null || livePreviewImp.getWindow() == null) {
                        livePreviewImp = new ImagePlus("Live preview", rawDisplay);
                        livePreviewImp.show();
                    } else {
                        // update processor and redraw
                        livePreviewImp.setProcessor(rawDisplay);
                        try { livePreviewImp.updateAndDraw(); } catch (Exception ignore) {}
                    }
                } catch (Throwable t) {
                    // Defensive logging so preview issues don't kill acquisition
                    if (studio != null) studio.logs().showError("Preview window error: " + t.getMessage());
                }
            });
        }
    }

    /**
     * Close and clear the live preview (safe to call from worker thread).
     */
    private void closeLivePreview() {
        synchronized (livePreviewLock) {
            final ImagePlus imp = livePreviewImp;
            if (imp != null) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (imp.getWindow() != null) {
                            imp.getWindow().close();
                        }
                    } catch (Throwable ignore) {}
                });
            }
            livePreviewImp = null;
        }
    }

    // CSV row holder
    private static class LocalizationCsvRow {
        int frame;
        double amp, x, y;
        String filename;
        LocalizationCsvRow(int frame, double amp, double x, double y, String filename) {
            this.frame = frame; this.amp = amp; this.x = x; this.y = y; this.filename = filename;
        }
    }

    private void showError(String msg) {
        if (studio != null) studio.logs().showError(msg);
        else System.err.println("SMLMLocalizer: " + msg);
    }

    // Helpers for simulation fallback (used only if processor == null)
    private String[] getTiffFileList(String folder) {
        try {
            File dir = new File(folder);
            File[] files = dir.listFiles((d, name) -> {
                String n = name.toLowerCase();
                return n.endsWith(".tif") || n.endsWith(".tiff");
            });
            if (files == null || files.length == 0) return new String[0];
            java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
            String[] out = new String[files.length];
            for (int i = 0; i < files.length; i++) out[i] = files[i].getAbsolutePath();
            return out;
        } catch (Exception e) {
            return new String[0];
        }
    }

    private double[][] loadSimImageFromPath(String fullPath) throws Exception {
        ij.ImagePlus imp = ij.IJ.openImage(fullPath);
        if (imp == null) throw new Exception("Cannot open image: " + fullPath);
        ij.process.ImageProcessor ip = imp.getProcessor().convertToFloatProcessor();
        int w = ip.getWidth();
        int h = ip.getHeight();
        double[][] image = new double[h][w];
        float[] pix = (float[]) ip.getPixels();
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                image[y][x] = pix[row + x];
            }
        }
        return image;
    }

    /**
     * Convert MM core pixel buffer to double[][]. (unchanged)
     */
    private double[][] toDoubleArray(Object pix, int w, int h) {
        double[][] image = new double[h][w];
        if (pix instanceof byte[]) {
            byte[] b = (byte[]) pix;
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) image[y][x] = (b[row + x] & 0xff);
            }
        } else if (pix instanceof short[]) {
            short[] s = (short[]) pix;
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) image[y][x] = (s[row + x] & 0xffff);
            }
        } else if (pix instanceof int[]) {
            int[] si = (int[]) pix;
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) image[y][x] = si[row + x];
            }
        } else {
            throw new IllegalArgumentException("Unrecognized pixel buffer type: " + (pix == null ? "null" : pix.getClass().getName()));
        }
        return image;
    }
}
