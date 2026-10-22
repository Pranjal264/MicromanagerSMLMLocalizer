package org.micromanager.smlm;

import java.util.*;

/**
 * Turns the localizations of a bead z-stack into a CalibrationCurve.
 *
 * Beads do not move, so localizations are grouped by position (a bead = all localizations within
 * beadRadiusNm of its first one). Groups seen in too few planes (noise, junk) are dropped. For each bead and
 * plane the median of ln(sigmaX/sigmaY) and of the widths is taken over the frames of that plane, and the
 * curve at each plane is the median over beads.
 *
 * z of frame f (1-based, as in the CSV) = zStart + floor((f - 1) / framesPerStep) * zStep.
 */
public final class CalibrationBuilder {

    public static final class Params {
        public double zStartNm = 0.0;
        public double zStepNm = 50.0;
        public int framesPerStep = 1;
        public double beadRadiusNm = 300.0;
        public double minPresence = 0.5;       // fraction of planes in which a bead must be found
    }

    private CalibrationBuilder() { }

    public static CalibrationCurve build(LocalizationTable t, Params p) {
        if (p.zStepNm == 0) throw new IllegalArgumentException("Z step must not be zero");
        if (p.framesPerStep < 1) throw new IllegalArgumentException("Frames per z step must be at least 1");
        if (p.beadRadiusNm <= 0) throw new IllegalArgumentException("Bead match radius must be positive");

        final int N = t.n;
        boolean[] ok = new boolean[N];
        int[] plane = new int[N];
        int maxPlane = -1;
        for (int i = 0; i < N; i++) {
            if (t.frame[i] >= 1 && t.sigmaX[i] > 0 && t.sigmaY[i] > 0
                    && Double.isFinite(t.x[i]) && Double.isFinite(t.y[i])
                    && Double.isFinite(t.sigmaX[i]) && Double.isFinite(t.sigmaY[i])) {
                ok[i] = true;
                plane[i] = (t.frame[i] - 1) / p.framesPerStep;
                maxPlane = Math.max(maxPlane, plane[i]);
            }
        }
        if (maxPlane < 0) throw new IllegalArgumentException("No usable localizations (all sigma values missing?)");
        final int nPlanes = maxPlane + 1;

        // ---- group localizations into beads by position
        final double R = p.beadRadiusNm, R2 = R * R;
        List<double[]> centers = new ArrayList<>();
        Map<Long, List<Integer>> grid = new HashMap<>();
        int[] cid = new int[N];
        Arrays.fill(cid, -1);
        for (int i = 0; i < N; i++) {
            if (!ok[i]) continue;
            long gx = (long) Math.floor(t.x[i] / R), gy = (long) Math.floor(t.y[i] / R);
            int best = -1; double bd = R2;
            for (long dx = -1; dx <= 1; dx++) for (long dy = -1; dy <= 1; dy++) {
                List<Integer> cell = grid.get(key(gx + dx, gy + dy));
                if (cell == null) continue;
                for (int c : cell) {
                    double ddx = centers.get(c)[0] - t.x[i], ddy = centers.get(c)[1] - t.y[i];
                    double d2 = ddx * ddx + ddy * ddy;
                    if (d2 <= bd) { bd = d2; best = c; }
                }
            }
            if (best < 0) {
                best = centers.size();
                centers.add(new double[]{t.x[i], t.y[i]});
                grid.computeIfAbsent(key(gx, gy), k -> new ArrayList<>()).add(best);
            }
            cid[i] = best;
        }

        // ---- keep groups that are present in enough planes
        BitSet[] seen = new BitSet[centers.size()];
        for (int i = 0; i < N; i++) {
            if (cid[i] < 0) continue;
            if (seen[cid[i]] == null) seen[cid[i]] = new BitSet(nPlanes);
            seen[cid[i]].set(plane[i]);
        }
        int minPlanes = Math.max(1, (int) Math.ceil(p.minPresence * nPlanes));
        int[] newId = new int[centers.size()];
        Arrays.fill(newId, -1);
        int beads = 0;
        for (int c = 0; c < newId.length; c++) {
            if (seen[c] != null && seen[c].cardinality() >= minPlanes) newId[c] = beads++;
        }
        if (beads == 0) {
            throw new IllegalArgumentException("No bead was found in at least " + minPlanes + " of " + nPlanes
                    + " planes. Lower 'min. presence', check frames per z step, or check the threshold used for localization.");
        }
        if ((long) beads * nPlanes >= (1L << 31)) throw new IllegalArgumentException("Too many beads/planes");

        // ---- per (bead, plane) medians
        long[] keys = new long[N];
        int m = 0;
        for (int i = 0; i < N; i++) {
            if (cid[i] < 0 || newId[cid[i]] < 0) continue;
            long k = (long) newId[cid[i]] * nPlanes + plane[i];
            keys[m++] = (k << 32) | i;
        }
        Arrays.sort(keys, 0, m);

        @SuppressWarnings({"unchecked", "rawtypes"})
        List<double[]>[] perPlane = new List[nPlanes];
        for (int g = 0; g < m; ) {
            long k = keys[g] >>> 32;
            int h = g;
            while (h < m && (keys[h] >>> 32) == k) h++;
            int len = h - g;
            double[] L = new double[len], sx = new double[len], sy = new double[len];
            for (int q = 0; q < len; q++) {
                int row = (int) (keys[g + q] & 0xffffffffL);
                sx[q] = t.sigmaX[row]; sy[q] = t.sigmaY[row]; L[q] = Math.log(sx[q] / sy[q]);
            }
            int pl = (int) (k % nPlanes);
            if (perPlane[pl] == null) perPlane[pl] = new ArrayList<>();
            perPlane[pl].add(new double[]{median(L), median(sx), median(sy)});
            g = h;
        }

        // ---- median over beads per plane
        List<double[]> out = new ArrayList<>();
        for (int pl = 0; pl < nPlanes; pl++) {
            List<double[]> v = perPlane[pl];
            if (v == null || v.isEmpty()) continue;
            int nb = v.size();
            double[] L = new double[nb], sx = new double[nb], sy = new double[nb];
            for (int i = 0; i < nb; i++) { L[i] = v.get(i)[0]; sx[i] = v.get(i)[1]; sy[i] = v.get(i)[2]; }
            out.add(new double[]{p.zStartNm + pl * p.zStepNm, median(L), median(sx), median(sy), nb});
        }
        int n = out.size();
        if (n < 3) throw new IllegalArgumentException("Calibration curve has only " + n + " plane(s)");
        double[] z = new double[n], Ls = new double[n], sxs = new double[n], sys = new double[n];
        int[] nb = new int[n];
        for (int i = 0; i < n; i++) {
            double[] r = out.get(i);
            z[i] = r[0]; Ls[i] = r[1]; sxs[i] = r[2]; sys[i] = r[3]; nb[i] = (int) r[4];
        }
        return new CalibrationCurve(z, Ls, sxs, sys, nb, beads);
    }

    private static long key(long gx, long gy) { return (gx << 32) ^ (gy & 0xffffffffL); }

    static double median(double[] a) {
        double[] s = a.clone();
        Arrays.sort(s);
        int n = s.length;
        return (n % 2 == 1) ? s[n / 2] : 0.5 * (s[n / 2 - 1] + s[n / 2]);
    }
}
