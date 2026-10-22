package org.micromanager.smlm;

import java.io.*;
import java.util.*;

/**
 * z as a polynomial of L = ln(sigmaX / sigmaY).
 *
 * The ratio is used (instead of the widths themselves) because it is dimensionless and mostly insensitive to
 * emitter brightness and to the absolute width, which differ between beads and dye molecules.
 * Far from focus both widths grow and the ratio folds back, so the fit is restricted to the monotonic
 * region around focus; localizations whose ratio lies outside it get z = NaN.
 */
public final class ZCalibration {

    /** A drop of this size (in L) below the running best ends the monotonic region. */
    private static final double MONOTONIC_TOLERANCE = 0.03;
    /** Planes at the ends of the monotonic region with a slope below this fraction of the maximum are dropped. */
    private static final double STEEP_FRACTION = 0.3;

    public final int degree;
    public final double lMid, lHalf;       // L is mapped to u = (L - lMid) / lHalf before evaluating the polynomial
    public final double[] coeffs;          // z = sum coeffs[k] * u^k
    public final double lMin, lMax;        // valid ln-ratio range
    public final double zMin, zMax;        // z range covered by the fit
    public final double rmsNm;             // RMS of the fit residual on the lookup table
    public final int roiSize;              // ROI used for the widths, 0 = unknown
    public final double pixelSizeNm;       // 0 = unknown
    public final CalibrationCurve curve;   // may be null for old files
    public final int fitFirst, fitLast;    // index range of curve used in the fit (-1 if unknown)

    private ZCalibration(int degree, double lMid, double lHalf, double[] coeffs, double lMin, double lMax,
                         double zMin, double zMax, double rmsNm, int roiSize, double pixelSizeNm,
                         CalibrationCurve curve, int fitFirst, int fitLast) {
        this.degree = degree; this.lMid = lMid; this.lHalf = lHalf; this.coeffs = coeffs;
        this.lMin = lMin; this.lMax = lMax; this.zMin = zMin; this.zMax = zMax; this.rmsNm = rmsNm;
        this.roiSize = roiSize; this.pixelSizeNm = pixelSizeNm; this.curve = curve;
        this.fitFirst = fitFirst; this.fitLast = fitLast;
    }

    // ------------------------------------------------------------------ evaluation

    public double zFromLog(double L) {
        if (!(L >= lMin && L <= lMax)) return Double.NaN;      // also catches NaN
        double u = (L - lMid) / lHalf;
        double z = 0;
        for (int k = coeffs.length - 1; k >= 0; k--) z = z * u + coeffs[k];
        return z;
    }

    /**
     * Estimate z from the calibrated (sigmaX(z), sigmaY(z)) curve, following the
     * astigmatic calibration approach used by ThunderSTORM-like workflows. The measured
     * width pair is projected onto each calibrated line segment; the closest point gives z.
     * The log-ratio interval remains a guard against measurements outside the calibrated branch.
     */
    public double z(double sigmaX, double sigmaY) {
        if (!(sigmaX > 0 && sigmaY > 0) || !Double.isFinite(sigmaX) || !Double.isFinite(sigmaY)) return Double.NaN;
        double logRatio = Math.log(sigmaX / sigmaY);
        if (!(logRatio >= lMin && logRatio <= lMax)) return Double.NaN;
        if (curve == null || curve.size() < 2) return zFromLog(logRatio); // legacy calibration file

        int first = fitFirst >= 0 && fitFirst < curve.size() ? fitFirst : 0;
        int last = fitLast >= first && fitLast < curve.size() ? fitLast : curve.size() - 1;
        if (last <= first) return zFromLog(logRatio);
        double bestD2 = Double.POSITIVE_INFINITY;
        double bestZ = Double.NaN;
        for (int i = first; i < last; i++) {
            double x0 = curve.sigmaX[i], y0 = curve.sigmaY[i];
            double dx = curve.sigmaX[i + 1] - x0, dy = curve.sigmaY[i + 1] - y0;
            double den = dx * dx + dy * dy;
            if (!(den > 0)) continue;
            double u = ((sigmaX - x0) * dx + (sigmaY - y0) * dy) / den;
            u = Math.max(0.0, Math.min(1.0, u));
            double ex = sigmaX - (x0 + u * dx), ey = sigmaY - (y0 + u * dy);
            double d2 = ex * ex + ey * ey;
            if (d2 < bestD2) {
                bestD2 = d2;
                bestZ = curve.z[i] + u * (curve.z[i + 1] - curve.z[i]);
            }
        }
        return bestZ;
    }

    /** z for every row (NaN where the ratio is outside the calibrated range), multiplied by zScale. */
    public double[] apply(LocalizationTable t, double zScale) {
        double[] out = new double[t.n];
        for (int i = 0; i < t.n; i++) out[i] = z(t.sigmaX[i], t.sigmaY[i]) * zScale;
        return out;
    }

    /**
     * Largest mean width sqrt(sigmaX * sigmaY) (nm) among the calibration planes used for the fit, or NaN if the
     * lookup table is not available. Far from focus the ratio folds back onto in-range values, but such
     * emitters are much wider than anything in the calibrated range, so this can be used to reject them.
     */
    public double maxMeanWidthNm() {
        if (curve == null || fitFirst < 0 || fitLast >= curve.size()) return Double.NaN;
        double m = 0;
        for (int i = fitFirst; i <= fitLast; i++) m = Math.max(m, Math.sqrt(curve.sigmaX[i] * curve.sigmaY[i]));
        return m > 0 ? m : Double.NaN;
    }

    /** True if sqrt(sigmaX * sigmaY) exceeds factor times the widest calibrated plane (never true if factor <= 0). */
    public boolean isTooWide(double sigmaX, double sigmaY, double factor) {
        if (!(factor > 0)) return false;
        double ref = maxMeanWidthNm();
        if (Double.isNaN(ref)) return false;
        return Math.sqrt(sigmaX * sigmaY) > factor * ref;
    }

    public String summary() {
        return String.format(Locale.ROOT, "degree %d, z %.0f to %.0f nm, fit RMS %.1f nm%s%s",
                degree, zMin, zMax, rmsNm,
                curve != null && curve.totalBeads > 0 ? ", " + curve.totalBeads + " beads" : "",
                roiSize > 0 ? ", ROI " + roiSize + " px" : "");
    }

    // ------------------------------------------------------------------ fitting

    /**
     * @param autoRange true: use the longest monotonic stretch of the curve; false: use planes with zLo <= z <= zHi
     */
    public static ZCalibration fit(CalibrationCurve c, int degree, boolean autoRange, double zLo, double zHi,
                                   int roiSize, double pixelSizeNm) {
        if (degree < 1 || degree > 8) throw new IllegalArgumentException("Polynomial degree must be 1..8");
        int n = c.size();
        int i0, i1;
        if (autoRange) {
            int[] r = longestMonotonicRun(c);
            i0 = r[0]; i1 = r[1];
        } else {
            double lo = Math.min(zLo, zHi), hi = Math.max(zLo, zHi);
            i0 = -1; i1 = -1;
            for (int i = 0; i < n; i++) {
                if (c.z[i] >= lo && c.z[i] <= hi) { if (i0 < 0) i0 = i; i1 = i; }
            }
            if (i0 < 0) throw new IllegalArgumentException("No calibration planes inside the chosen z range");
        }
        int m = i1 - i0 + 1;
        if (m < degree + 2) {
            throw new IllegalArgumentException("Only " + m + " calibration planes in the fit range; need at least "
                    + (degree + 2) + " for degree " + degree + ". Lower the degree, widen the z range, or use a finer z step.");
        }
        double lMin = Double.POSITIVE_INFINITY, lMax = Double.NEGATIVE_INFINITY;
        double zMin = Double.POSITIVE_INFINITY, zMax = Double.NEGATIVE_INFINITY;
        for (int i = i0; i <= i1; i++) {
            lMin = Math.min(lMin, c.logRatio[i]); lMax = Math.max(lMax, c.logRatio[i]);
            zMin = Math.min(zMin, c.z[i]); zMax = Math.max(zMax, c.z[i]);
        }
        double lMid = 0.5 * (lMin + lMax);
        double lHalf = Math.max(0.5 * (lMax - lMin), 1e-9);
        double[] u = new double[m], y = new double[m];
        for (int i = 0; i < m; i++) { u[i] = (c.logRatio[i0 + i] - lMid) / lHalf; y[i] = c.z[i0 + i]; }
        double[] coeffs = polyFit(u, y, degree);

        double ss = 0;
        for (int i = 0; i < m; i++) {
            double z = 0;
            for (int k = degree; k >= 0; k--) z = z * u[i] + coeffs[k];
            ss += (z - y[i]) * (z - y[i]);
        }
        return new ZCalibration(degree, lMid, lHalf, coeffs, lMin, lMax, zMin, zMax, Math.sqrt(ss / m),
                roiSize, pixelSizeNm, c, i0, i1);
    }

    /**
     * Longest stretch of the curve along which L changes monotonically (in the overall direction of the data),
     * tolerating noise: a stretch ends once L has retreated by more than MONOTONIC_TOLERANCE from its best value.
     */
    static int[] longestMonotonicRun(CalibrationCurve c) {
        int n = c.size();
        if (n < 3) return new int[]{0, n - 1};
        // overall direction from the slope of a straight-line fit
        double mz = 0, mL = 0;
        for (int i = 0; i < n; i++) { mz += c.z[i]; mL += c.logRatio[i]; }
        mz /= n; mL /= n;
        double cov = 0;
        for (int i = 0; i < n; i++) cov += (c.z[i] - mz) * (c.logRatio[i] - mL);
        double sign = cov >= 0 ? 1 : -1;

        // 3-point median to suppress single-plane outliers
        double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            if (i == 0 || i == n - 1) { s[i] = sign * c.logRatio[i]; continue; }
            double a = c.logRatio[i - 1], b = c.logRatio[i], d = c.logRatio[i + 1];
            s[i] = sign * Math.max(Math.min(a, b), Math.min(Math.max(a, b), d));
        }
        int bestA = 0, bestB = 0;
        for (int a = 0; a < n; a++) {
            double best = s[a];
            int b = a;
            for (int j = a + 1; j < n; j++) {
                if (s[j] < best - MONOTONIC_TOLERANCE) break;
                best = Math.max(best, s[j]);
                b = j;
            }
            if (b - a > bestB - bestA) { bestA = a; bestB = b; }
        }
        // trim to the true extremes of the run
        int top = bestA;
        for (int j = bestA; j <= bestB; j++) if (s[j] >= s[top]) top = j;
        int bot = bestA;
        for (int j = bestA; j <= top; j++) if (s[j] <= s[bot]) bot = j;
        return trimToSteepPart(c, s, bot, top);
    }

    /**
     * Near the turning points of the ratio curve L hardly changes with z, so a small error in the measured
     * ratio becomes a huge error in z. Drop planes at both ends where the slope is below
     * STEEP_FRACTION of the steepest slope in the run.
     */
    private static int[] trimToSteepPart(CalibrationCurve c, double[] s, int a, int b) {
        int len = b - a + 1;
        if (len < 7) return new int[]{a, b};
        double[] slope = new double[len - 1];
        double max = 0;
        for (int i = 0; i < len - 1; i++) {
            slope[i] = Math.abs(s[a + i + 1] - s[a + i]) / Math.max(1e-12, Math.abs(c.z[a + i + 1] - c.z[a + i]));
        }
        double[] sm = new double[len - 1];                       // 3-point mean
        for (int i = 0; i < len - 1; i++) {
            double acc = 0; int cnt = 0;
            for (int j = Math.max(0, i - 1); j <= Math.min(len - 2, i + 1); j++) { acc += slope[j]; cnt++; }
            sm[i] = acc / cnt;
            max = Math.max(max, sm[i]);
        }
        int lo = 0, hi = len - 2;
        while (lo < hi && sm[lo] < STEEP_FRACTION * max) lo++;
        while (hi > lo && sm[hi] < STEEP_FRACTION * max) hi--;
        int na = a + lo, nb = a + hi + 1;
        return (nb - na + 1 >= 5) ? new int[]{na, nb} : new int[]{a, b};
    }

    private static double[] polyFit(double[] u, double[] y, int degree) {
        int d = degree + 1;
        double[][] a = new double[d][d + 1];
        for (int i = 0; i < u.length; i++) {
            double[] pw = new double[2 * degree + 1];
            pw[0] = 1;
            for (int k = 1; k < pw.length; k++) pw[k] = pw[k - 1] * u[i];
            for (int r = 0; r < d; r++) {
                for (int cc = 0; cc < d; cc++) a[r][cc] += pw[r + cc];
                a[r][d] += y[i] * pw[r];
            }
        }
        for (int col = 0; col < d; col++) {
            int piv = col;
            for (int r = col + 1; r < d; r++) if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) piv = r;
            if (Math.abs(a[piv][col]) < 1e-12) throw new IllegalArgumentException("Polynomial fit is singular; lower the degree");
            double[] tmp = a[col]; a[col] = a[piv]; a[piv] = tmp;
            for (int r = 0; r < d; r++) {
                if (r == col) continue;
                double f = a[r][col] / a[col][col];
                for (int cc = col; cc <= d; cc++) a[r][cc] -= f * a[col][cc];
            }
        }
        double[] x = new double[d];
        for (int r = 0; r < d; r++) x[r] = a[r][d] / a[r][r];
        return x;
    }

    // ------------------------------------------------------------------ files

    private static String join(double[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(Double.toString(a[i])); }
        return sb.toString();
    }
    private static String join(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
        return sb.toString();
    }
    private static double[] parseD(String s) {
        if (s == null || s.trim().isEmpty()) return new double[0];
        String[] p = s.split(",");
        double[] r = new double[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Double.parseDouble(p[i].trim());
        return r;
    }

    /** Save as a readable key=value text file (.zcal). The lookup table is stored inside, so it can be re-plotted. */
    public void save(File f) throws IOException {
        Map<String, String> m = new TreeMap<>();
        m.put("format", "smlm-zcal-1");
        m.put("model", "nearest-segment inversion of calibrated sigmaX(z),sigmaY(z); log-ratio branch guard; polynomial fallback for legacy curves");
        m.put("degree", Integer.toString(degree));
        m.put("lMid", Double.toString(lMid)); m.put("lHalf", Double.toString(lHalf));
        m.put("coeffs", join(coeffs));
        m.put("lMin", Double.toString(lMin)); m.put("lMax", Double.toString(lMax));
        m.put("zMin", Double.toString(zMin)); m.put("zMax", Double.toString(zMax));
        m.put("rmsNm", Double.toString(rmsNm));
        m.put("roiSize", Integer.toString(roiSize));
        m.put("pixelSizeNm", Double.toString(pixelSizeNm));
        m.put("fitFirst", Integer.toString(fitFirst)); m.put("fitLast", Integer.toString(fitLast));
        if (curve != null) {
            m.put("curve.z", join(curve.z)); m.put("curve.logRatio", join(curve.logRatio));
            m.put("curve.sigmaX", join(curve.sigmaX)); m.put("curve.sigmaY", join(curve.sigmaY));
            m.put("curve.beads", join(curve.beads)); m.put("curve.totalBeads", Integer.toString(curve.totalBeads));
        }
        try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
            pw.println("# SMLM localizer z calibration (astigmatic width-curve inversion). Saved " + new Date());
            for (Map.Entry<String, String> e : m.entrySet()) pw.println(e.getKey() + "=" + e.getValue());
        }
    }

    public static ZCalibration load(File f) throws IOException {
        Properties p = new Properties();
        try (InputStream is = new FileInputStream(f)) { p.load(is); }
        if (!"smlm-zcal-1".equals(p.getProperty("format"))) throw new IOException("Not a z calibration file: " + f.getName());
        try {
            CalibrationCurve cv = null;
            if (p.getProperty("curve.z") != null) {
                double[] bd = parseD(p.getProperty("curve.beads"));
                int[] b = new int[bd.length];
                for (int i = 0; i < b.length; i++) b[i] = (int) bd[i];
                cv = new CalibrationCurve(parseD(p.getProperty("curve.z")), parseD(p.getProperty("curve.logRatio")),
                        parseD(p.getProperty("curve.sigmaX")), parseD(p.getProperty("curve.sigmaY")), b,
                        Integer.parseInt(p.getProperty("curve.totalBeads", "0")));
            }
            return new ZCalibration(Integer.parseInt(p.getProperty("degree")),
                    Double.parseDouble(p.getProperty("lMid")), Double.parseDouble(p.getProperty("lHalf")),
                    parseD(p.getProperty("coeffs")),
                    Double.parseDouble(p.getProperty("lMin")), Double.parseDouble(p.getProperty("lMax")),
                    Double.parseDouble(p.getProperty("zMin")), Double.parseDouble(p.getProperty("zMax")),
                    Double.parseDouble(p.getProperty("rmsNm")),
                    Integer.parseInt(p.getProperty("roiSize", "0")), Double.parseDouble(p.getProperty("pixelSizeNm", "0")),
                    cv, Integer.parseInt(p.getProperty("fitFirst", "-1")), Integer.parseInt(p.getProperty("fitLast", "-1")));
        } catch (RuntimeException e) {
            throw new IOException("Calibration file is damaged or incomplete: " + e.getMessage());
        }
    }
}
