package org.micromanager.smlm;

import ij.ImagePlus;
import ij.process.FloatProcessor;

import javax.swing.*;
import java.util.List;

/**
 * NOTE: coordinates from Localization are in image pixels. This accumulator multiplies
 * coordinates by the configured 'mag' when mapping into the cumulative (magnified) canvas.
 */
public class LocalizationAccumulator {

    private FloatProcessor cumulativeFP;
    private ImagePlus cumulativeImp;
    private volatile boolean displayCreated = false;
    private volatile float runningMax = 1.0f;
    private final Object lock = new Object();

    // stored canvas geometry + magnification
    private int camW = -1;
    private int camH = -1;
    private int mag = 1;

    public void reset() {
        synchronized (lock) {
            cumulativeFP = null;
            cumulativeImp = null;
            displayCreated = false;
            runningMax = 1.0f;
            camW = camH = -1;
            mag = 1;
        }
    }

    /**
     * Initialize accumulator if not created. Stores camW/camH and magnification.
     * If magnification or cam size changed since last init, recreate the accumulator.
     */
    public void initIfNeeded(int camW, int camH, int mag) {
        synchronized (lock) {
            boolean needCreate = false;
            if (cumulativeFP == null) {
                needCreate = true;
            } else if (this.camW != camW || this.camH != camH || this.mag != mag) {
                // geometry changed — recreate accumulator
                needCreate = true;
            }
            if (needCreate) {
                this.camW = camW;
                this.camH = camH;
                this.mag = Math.max(1, mag);
                int cumulW = camW * this.mag;
                int cumulH = camH * this.mag;
                cumulativeFP = new FloatProcessor(cumulW, cumulH);
                cumulativeImp = new ImagePlus("Localizations (mag=" + this.mag + ")", cumulativeFP);
                runningMax = 1.0f;
                displayCreated = false;
            }
        }
    }

    /**
     * Add a list of localizations into the accumulator.
     * Each Localization.x/y are in image coordinates (pixels). They are multiplied by 'mag'
     * and rounded to nearest integer before being used as indices in the cumulative image.
     */
    public void addLocalizations(List<Localization> locs) {
        if (locs == null || locs.isEmpty()) return;
        synchronized (lock) {
            if (cumulativeFP == null) return;
            final int w = cumulativeFP.getWidth();
            final int h = cumulativeFP.getHeight();
            final int currentMag = this.mag;
            for (Localization L : locs) {
                // Multiply by mag to map from image coords -> magnified canvas coords
                int ix = (int) Math.round(L.x * currentMag);
                int iy = (int) Math.round(L.y * currentMag);
                if (ix >= 0 && ix < w && iy >= 0 && iy < h) {
                    float cur = cumulativeFP.getf(ix, iy);
                    cur += 1.0f;
                    cumulativeFP.setf(ix, iy, cur);
                    if (cur > runningMax) runningMax = cur;
                }
            }
        }
    }

    /**
     * Update the ImageJ display on the EDT. Shows the window first time, then updates.
     */
    public void updateDisplay() {
        synchronized (lock) {
            if (cumulativeImp == null) return;
            final float vmax = runningMax;
            SwingUtilities.invokeLater(() -> {
                if (!displayCreated) {
                    cumulativeImp.show();
                    displayCreated = true;
                } else {
                    cumulativeImp.updateAndDraw();
                }
                try {
                    cumulativeImp.getProcessor().setMinAndMax(0, Math.max(1.0, vmax));
                } catch (Exception ignore) {}
            });
        }
    }

    /**
     * Clears the accumulator (sets all pixels to zero) and resets running max.
     */
    public void clear() {
        synchronized (lock) {
            if (cumulativeFP != null) {
                float[] px = (float[]) cumulativeFP.getPixels();
                for (int i = 0; i < px.length; i++) px[i] = 0f;
                runningMax = 1.0f;
            }
            if (cumulativeImp != null) SwingUtilities.invokeLater(cumulativeImp::updateAndDraw);
        }
    }
}
