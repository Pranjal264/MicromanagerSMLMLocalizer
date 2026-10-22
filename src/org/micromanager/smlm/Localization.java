package org.micromanager.smlm;

/**
 * One localized emitter. All lengths are in camera pixels (the conversion to nm is done
 * at export time using the pixel size from the GUI).
 *
 * sigmaX / sigmaY are the PSF widths estimated from the phasor magnitudes. They are NaN
 * if the width could not be estimated for this ROI (e.g. DC <= 0 after background removal).
 */
public final class Localization {
    public final int frame;
    public final double amplitude;
    public final double x;
    public final double y;
    public final double sigmaX;
    public final double sigmaY;

    public Localization(int frame, double amplitude, double x, double y,
                        double sigmaX, double sigmaY) {
        this.frame = frame;
        this.amplitude = amplitude;
        this.x = x;
        this.y = y;
        this.sigmaX = sigmaX;
        this.sigmaY = sigmaY;
    }

    /** Convenience constructor for callers that have no width information. */
    public Localization(int frame, double amplitude, double x, double y) {
        this(frame, amplitude, x, y, Double.NaN, Double.NaN);
    }
}
