package org.micromanager.smlm;

import ij.ImageListener;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.ContrastEnhancer;
import ij.process.FloatProcessor;

import javax.swing.SwingUtilities;
import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Accumulates localizations into a magnified histogram image.
 *
 * Design notes (this is what fixes the "histogram window never comes back" bug):
 *  - The counts live in our own float[] and are never owned by an ImagePlus. When the
 *    user closes the ImageJ window, ImageJ may release the processor's pixels; our data
 *    is unaffected.
 *  - Whether the window is open is checked every time we draw, and a fresh ImagePlus is
 *    created around the same float[] when it is not. No "displayCreated" flag to go stale.
 *  - If the user closes the window during a run it stays closed (periodic updates do not
 *    keep re-opening it), but a new acquisition and the final update bring it back.
 *
 * Localization x/y are in camera pixels; they are multiplied by 'mag' to index the canvas.
 */
public class LocalizationAccumulator {

    /** Refuse canvases larger than this many pixels (~1 GB of floats). */
    private static final long MAX_CANVAS_PIXELS = 1L << 28;

    private final Object lock = new Object();

    // ---- data (guarded by lock) ----
    private float[] data;
    private int width = -1, height = -1;       // magnified canvas size
    private int camW = -1, camH = -1, mag = 1;
    private double pixelSizeNm = 0.0;

    // ---- display (touched on the EDT) ----
    private volatile ImagePlus imp;
    private volatile boolean suppressed = false; // true after the user closed the window

    private final ImageListener closeListener = new ImageListener() {
        @Override public void imageOpened(ImagePlus i) { }
        @Override public void imageUpdated(ImagePlus i) { }
        @Override public void imageClosed(ImagePlus i) {
            if (i != null && i == imp) {
                imp = null;
                suppressed = true;
            }
        }
    };

    public LocalizationAccumulator() {
        ImagePlus.addImageListener(closeListener);
    }

    /** Drop everything (data and window reference). */
    public void reset() {
        synchronized (lock) {
            data = null;
            width = height = camW = camH = -1;
            mag = 1;
        }
        suppressed = false;
    }

    /** Call at the start of every acquisition: zero the counts and allow the window to open. */
    public void beginAcquisition() {
        synchronized (lock) {
            if (data != null) Arrays.fill(data, 0f);
        }
        suppressed = false;
    }

    /**
     * Allocate the canvas if needed (or if camera size / magnification changed).
     * @throws IllegalStateException if the requested canvas is unreasonably large
     */
    public void initIfNeeded(int camW, int camH, int mag, double pixelSizeNm) {
        synchronized (lock) {
            int m = Math.max(1, mag);
            this.pixelSizeNm = pixelSizeNm;
            if (data != null && this.camW == camW && this.camH == camH && this.mag == m) return;

            long cw = (long) camW * m, ch = (long) camH * m;
            if (cw * ch > MAX_CANVAS_PIXELS) {
                throw new IllegalStateException("Reconstruction canvas too large (" + cw + " x " + ch
                        + " px). Reduce the magnification.");
            }
            this.camW = camW;
            this.camH = camH;
            this.mag = m;
            this.width = (int) cw;
            this.height = (int) ch;
            this.data = new float[this.width * this.height];
        }
    }

    /** Add localizations (image-pixel coordinates). Safe to call from several threads. */
    public void addLocalizations(List<Localization> locs) {
        if (locs == null || locs.isEmpty()) return;
        synchronized (lock) {
            if (data == null) return;
            final int w = width, h = height, m = mag;
            for (Localization L : locs) {
                int ix = (int) Math.round(L.x * m);
                int iy = (int) Math.round(L.y * m);
                if (ix >= 0 && ix < w && iy >= 0 && iy < h) {
                    data[iy * w + ix] += 1.0f;
                }
            }
        }
    }

    /** Periodic refresh: does not re-open a window the user closed. */
    public void updateDisplay() { updateDisplay(false); }

    /**
     * Refresh the display on the EDT, creating the window if it is not open.
     * @param forceShow open the window even if the user closed it earlier in this run
     */
    public void updateDisplay(boolean forceShow) {
        final float[] d;
        final int w, h, m;
        final double px;
        synchronized (lock) {
            if (data == null) return;
            d = data; w = width; h = height; m = mag; px = pixelSizeNm;
        }
        SwingUtilities.invokeLater(() -> {
            boolean open = isWindowOpen();
            if (!open) {
                if (suppressed && !forceShow) return;
                suppressed = false;
                ImagePlus created = new ImagePlus(title(m), new FloatProcessor(w, h, d));
                applyCalibration(created, m, px);
                imp = created;
                created.show();
            } else {
                ImagePlus cur = imp;
                if (cur.getWidth() != w || cur.getHeight() != h || cur.getProcessor().getPixels() != d) {
                    cur.setProcessor(title(m), new FloatProcessor(w, h, d));
                    applyCalibration(cur, m, px);
                }
            }
            ImagePlus cur = imp;
            if (cur == null) return;
            try {
                new ContrastEnhancer().stretchHistogram(cur, 0.5);
            } catch (Exception ignore) { }
            cur.updateAndDraw();
        });
    }

    /** Zero the counts (the "Clear histogram" button). */
    public void clear() {
        synchronized (lock) {
            if (data != null) Arrays.fill(data, 0f);
        }
        SwingUtilities.invokeLater(() -> {
            ImagePlus cur = imp;
            if (cur != null && isWindowOpen()) cur.updateAndDraw();
        });
    }

    /**
     * Save the reconstruction as a 32-bit TIFF. The pixel calibration is written in nm
     * (pixelSize / mag per histogram pixel) so Fiji reports real units.
     * Works whether or not the display window is open.
     */
    public boolean saveTiff(File file) {
        final float[] d;
        final int w, h, m;
        final double px;
        synchronized (lock) {
            if (data == null) return false;
            d = data; w = width; h = height; m = mag; px = pixelSizeNm;
        }
        ImagePlus out = new ImagePlus(title(m), new FloatProcessor(w, h, d));
        applyCalibration(out, m, px);
        return new FileSaver(out).saveAsTiff(file.getAbsolutePath());
    }

    // ---- helpers ----

    private boolean isWindowOpen() {
        ImagePlus cur = imp;
        return cur != null && cur.getWindow() != null && !cur.getWindow().isClosed();
    }

    private static String title(int mag) {
        return "Localizations (mag=" + mag + ")";
    }

    private static void applyCalibration(ImagePlus target, int mag, double pixelSizeNm) {
        if (pixelSizeNm > 0) {
            Calibration c = target.getCalibration();
            c.pixelWidth = pixelSizeNm / mag;
            c.pixelHeight = pixelSizeNm / mag;
            c.setUnit("nm");
        }
    }
}
