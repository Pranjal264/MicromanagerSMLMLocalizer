package org.micromanager.smlm;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;

import java.awt.Color;
import java.util.Arrays;
import java.util.Locale;

/**
 * Renders a 3D localization table (nm) into
 *   - a colour image: hue = z (blue = lowest, red = highest), brightness = local density
 *   - optionally a z-stack of density slices
 * Each localization is drawn as a small Gaussian (renderSigmaNm) instead of a single pixel.
 * Nothing here is shown or saved; the caller decides.
 */
public final class Renderer3D {

    public static final class Params {
        public double pixelNm = 20.0;
        public double sigmaNm = 20.0;
        public double zBinNm = 50.0;
        public boolean autoZRange = true;      // true: 0.5..99.5 percentile of z
        public double zMinNm = -500, zMaxNm = 500;
        public boolean makeStack = true;
    }

    public static final class Result {
        public ImagePlus color;
        public ImagePlus stack;                // null if not requested
        public double zLo, zHi;
        public int used, skipped;
    }

    private static final long MAX_PLANE_PIXELS = 1L << 26;     // 64 Mpx per image
    private static final long MAX_STACK_VOXELS = 1L << 28;

    private Renderer3D() { }

    public static Result render(LocalizationTable t, Params p) {
        if (t.z == null) throw new IllegalArgumentException("Table has no z values");
        if (p.pixelNm <= 0 || p.sigmaNm <= 0 || p.zBinNm <= 0) throw new IllegalArgumentException("Render sizes must be positive");

        final int n = t.n;
        double[] zs = new double[n];
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(t.x[i]) && Double.isFinite(t.y[i]) && Double.isFinite(t.z[i])) zs[cnt++] = t.z[i];
        }
        if (cnt == 0) throw new IllegalArgumentException("No localizations with a valid z");

        double zLo, zHi;
        if (p.autoZRange) {
            double[] s = Arrays.copyOf(zs, cnt);
            Arrays.sort(s);
            zLo = s[(int) Math.floor(0.005 * (cnt - 1))];
            zHi = s[(int) Math.ceil(0.995 * (cnt - 1))];
        } else {
            zLo = Math.min(p.zMinNm, p.zMaxNm);
            zHi = Math.max(p.zMinNm, p.zMaxNm);
        }
        if (!(zHi > zLo)) { zLo -= 1; zHi += 1; }

        int[] sel = new int[cnt];
        int m = 0;
        double xmin = Double.POSITIVE_INFINITY, ymin = Double.POSITIVE_INFINITY;
        double xmax = Double.NEGATIVE_INFINITY, ymax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (!(Double.isFinite(t.x[i]) && Double.isFinite(t.y[i]) && Double.isFinite(t.z[i]))) continue;
            if (t.z[i] < zLo || t.z[i] > zHi) continue;
            sel[m++] = i;
            xmin = Math.min(xmin, t.x[i]); xmax = Math.max(xmax, t.x[i]);
            ymin = Math.min(ymin, t.y[i]); ymax = Math.max(ymax, t.y[i]);
        }
        if (m == 0) throw new IllegalArgumentException("No localizations inside the z range");

        final double sigPx = Math.max(0.3, p.sigmaNm / p.pixelNm);
        final int r = (int) Math.ceil(3 * sigPx);
        final int pad = r + 1;
        final int nx = (int) Math.ceil((xmax - xmin) / p.pixelNm) + 1 + 2 * pad;
        final int ny = (int) Math.ceil((ymax - ymin) / p.pixelNm) + 1 + 2 * pad;
        if ((long) nx * ny > MAX_PLANE_PIXELS) {
            throw new IllegalArgumentException("Image would be " + nx + " x " + ny + " px. Increase the xy pixel size.");
        }
        final int nz = Math.max(1, (int) Math.ceil((zHi - zLo) / p.zBinNm));
        boolean stackOk = p.makeStack;
        if (stackOk && (long) nx * ny * nz > MAX_STACK_VOXELS) {
            throw new IllegalArgumentException("z-stack would have " + nx + " x " + ny + " x " + nz
                    + " voxels. Increase the xy pixel size or the z bin, or untick the z-stack.");
        }

        float[] R = new float[nx * ny], G = new float[nx * ny], B = new float[nx * ny];
        float[][] planes = null;
        if (stackOk) {
            planes = new float[nz][];
            for (int k = 0; k < nz; k++) planes[k] = new float[nx * ny];
        }

        // 256-entry colour table, blue -> red
        float[][] lut = new float[256][3];
        for (int i = 0; i < 256; i++) {
            Color c = Color.getHSBColor((float) (0.7 * (1.0 - i / 255.0)), 1f, 1f);
            lut[i][0] = c.getRed() / 255f; lut[i][1] = c.getGreen() / 255f; lut[i][2] = c.getBlue() / 255f;
        }

        double[] wx = new double[2 * r + 2], wy = new double[2 * r + 2];
        for (int q = 0; q < m; q++) {
            int i = sel[q];
            double cx = (t.x[i] - xmin) / p.pixelNm + pad;
            double cy = (t.y[i] - ymin) / p.pixelNm + pad;
            int x0 = (int) Math.floor(cx) - r, y0 = (int) Math.floor(cy) - r;
            double sx = 0, sy = 0;
            for (int a = 0; a < wx.length; a++) {
                double dx = (x0 + a) - cx, dy = (y0 + a) - cy;
                wx[a] = Math.exp(-dx * dx / (2 * sigPx * sigPx)); sx += wx[a];
                wy[a] = Math.exp(-dy * dy / (2 * sigPx * sigPx)); sy += wy[a];
            }
            double frac = (t.z[i] - zLo) / (zHi - zLo);
            float[] col = lut[Math.max(0, Math.min(255, (int) Math.round(frac * 255)))];
            float[] plane = null;
            if (planes != null) plane = planes[Math.max(0, Math.min(nz - 1, (int) Math.floor((t.z[i] - zLo) / p.zBinNm)))];
            for (int b = 0; b < wy.length; b++) {
                int py = y0 + b;
                double wyb = wy[b] / sy;
                int row = py * nx;
                for (int a = 0; a < wx.length; a++) {
                    float w = (float) (wx[a] / sx * wyb);
                    int idx = row + x0 + a;
                    R[idx] += w * col[0]; G[idx] += w * col[1]; B[idx] += w * col[2];
                    if (plane != null) plane[idx] += w;
                }
            }
        }

        // brightness scaling: 99.9th percentile of the non-zero pixels, so a few hot pixels do not darken the rest
        float bmax = 0;
        for (int i = 0; i < R.length; i++) bmax = Math.max(bmax, Math.max(R[i], Math.max(G[i], B[i])));
        float scale = 1f;
        if (bmax > 0) {
            int bins = 4096;
            long[] hist = new long[bins];
            long nonzero = 0;
            for (int i = 0; i < R.length; i++) {
                float v = Math.max(R[i], Math.max(G[i], B[i]));
                if (v <= 0) continue;
                hist[Math.min(bins - 1, (int) (v / bmax * (bins - 1)))]++;
                nonzero++;
            }
            long target = (long) Math.ceil(0.999 * nonzero), acc = 0;
            int bin = bins - 1;
            for (int b = 0; b < bins; b++) { acc += hist[b]; if (acc >= target) { bin = b; break; } }
            float top = Math.max(1e-9f, (bin + 1f) / bins * bmax);
            scale = 1f / top;
        }
        int[] rgb = new int[nx * ny];
        for (int i = 0; i < rgb.length; i++) {
            int rr = Math.min(255, Math.round(255f * R[i] * scale));
            int gg = Math.min(255, Math.round(255f * G[i] * scale));
            int bb = Math.min(255, Math.round(255f * B[i] * scale));
            rgb[i] = (rr << 16) | (gg << 8) | bb;
        }

        Result res = new Result();
        res.zLo = zLo; res.zHi = zHi; res.used = m; res.skipped = n - m;
        res.color = new ImagePlus(String.format(Locale.ROOT, "3D localizations (z %.0f to %.0f nm, blue to red)", zLo, zHi),
                new ColorProcessor(nx, ny, rgb));
        setCal(res.color, p.pixelNm, 0);

        if (planes != null) {
            ImageStack st = new ImageStack(nx, ny);
            for (int k = 0; k < nz; k++) {
                st.addSlice(String.format(Locale.ROOT, "z = %.0f nm", zLo + (k + 0.5) * p.zBinNm), new FloatProcessor(nx, ny, planes[k]));
            }
            res.stack = new ImagePlus("3D localizations z-stack", st);
            setCal(res.stack, p.pixelNm, p.zBinNm);
        }
        return res;
    }

    private static void setCal(ImagePlus imp, double pixelNm, double zBinNm) {
        Calibration c = imp.getCalibration();
        c.pixelWidth = pixelNm; c.pixelHeight = pixelNm;
        if (zBinNm > 0) c.pixelDepth = zBinNm;
        c.setUnit("nm");
    }
}
