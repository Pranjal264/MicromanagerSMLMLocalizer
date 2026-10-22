package org.micromanager.smlm;

/** Applies a z calibration to a localization table (the non-GUI part of "compute z"). */
public final class ThreeDPipeline {

    public static final class ApplyResult {
        public LocalizationTable table;     // with z; rejected rows removed or z = NaN depending on the option
        public int total;
        public int outOfRange;              // ln(sigmaX/sigmaY) outside the calibrated range, or width missing
        public int tooWide;                 // ratio in range but emitter much wider than anything calibrated
        public int kept() { return total - outOfRange - tooWide; }
    }

    private ThreeDPipeline() { }

    /**
     * @param zScale      multiplies z (e.g. refractive-index mismatch correction); 1 = none
     * @param widthFactor reject emitters with sqrt(sigmaX*sigmaY) above widthFactor * calibrated maximum; 0 = off
     * @param dropRejected true: remove rejected rows; false: keep them with z = NaN
     */
    public static ApplyResult apply(LocalizationTable t, ZCalibration cal, double zScale,
                                    double widthFactor, boolean dropRejected) {
        ApplyResult r = new ApplyResult();
        r.total = t.n;
        double[] z = new double[t.n];
        boolean[] keep = new boolean[t.n];
        for (int i = 0; i < t.n; i++) {
            double zi = cal.z(t.sigmaX[i], t.sigmaY[i]);
            if (Double.isNaN(zi)) {
                r.outOfRange++;
                z[i] = Double.NaN;
            } else if (cal.isTooWide(t.sigmaX[i], t.sigmaY[i], widthFactor)) {
                r.tooWide++;
                z[i] = Double.NaN;
            } else {
                z[i] = zi * zScale;
                keep[i] = true;
            }
        }
        LocalizationTable withZ = t.withZ(z);
        r.table = dropRejected ? withZ.select(keep) : withZ;
        return r;
    }
}
