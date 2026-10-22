package org.micromanager.smlm;

import java.io.*;
import java.util.Arrays;
import java.util.Locale;

/**
 * The measured calibration data: per z plane the PSF widths and ln(sigmaX/sigmaY), sorted by z.
 * This is the "lookup table"; ZCalibration fits z(ln ratio) to it.
 */
public final class CalibrationCurve {
    public final double[] z, logRatio, sigmaX, sigmaY;   // nm (logRatio dimensionless)
    public final int[] beads;                             // beads contributing at each plane
    public final int totalBeads;

    public CalibrationCurve(double[] z, double[] logRatio, double[] sigmaX, double[] sigmaY,
                            int[] beads, int totalBeads) {
        int n = z.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(z[a], z[b]));
        this.z = new double[n]; this.logRatio = new double[n];
        this.sigmaX = new double[n]; this.sigmaY = new double[n]; this.beads = new int[n];
        for (int k = 0; k < n; k++) {
            int i = idx[k];
            this.z[k] = z[i]; this.logRatio[k] = logRatio[i];
            this.sigmaX[k] = sigmaX[i]; this.sigmaY[k] = sigmaY[i]; this.beads[k] = beads[i];
        }
        this.totalBeads = totalBeads;
    }

    public int size() { return z.length; }

    /** Load a lookup table CSV with columns z_nm, sigmax_nm, sigmay_nm (optionally beads). */
    public static CalibrationCurve fromLutCsv(File f) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String header = br.readLine();
            if (header == null) throw new IOException("Empty file");
            String[] h = header.split(",", -1);
            int cz = -1, cx = -1, cy = -1, cb = -1;
            for (int i = 0; i < h.length; i++) {
                String k = h[i].trim().toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
                if (k.equals("znm") || k.equals("z")) cz = i;
                else if (k.equals("sigmaxnm") || k.equals("sigmax")) cx = i;
                else if (k.equals("sigmaynm") || k.equals("sigmay")) cy = i;
                else if (k.equals("beads")) cb = i;
            }
            if (cz < 0 || cx < 0 || cy < 0) {
                throw new IOException("Lookup table needs columns z_nm, sigmax_nm, sigmay_nm (found: " + header + ")");
            }
            java.util.List<double[]> rows = new java.util.ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] c = line.split(",", -1);
                double zz = Double.parseDouble(c[cz].trim());
                double sx = Double.parseDouble(c[cx].trim());
                double sy = Double.parseDouble(c[cy].trim());
                if (!(sx > 0 && sy > 0)) continue;
                rows.add(new double[]{zz, sx, sy, cb >= 0 ? Double.parseDouble(c[cb].trim()) : 1});
            }
            int n = rows.size();
            if (n < 3) throw new IOException("Lookup table has fewer than 3 usable rows");
            double[] z = new double[n], L = new double[n], sx = new double[n], sy = new double[n];
            int[] b = new int[n];
            for (int i = 0; i < n; i++) {
                double[] r = rows.get(i);
                z[i] = r[0]; sx[i] = r[1]; sy[i] = r[2]; L[i] = Math.log(r[1] / r[2]); b[i] = (int) r[3];
            }
            return new CalibrationCurve(z, L, sx, sy, b, 0);
        }
    }

    public void writeCsv(File f) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
            pw.println("z_nm,sigmax_nm,sigmay_nm,log_ratio,beads");
            for (int i = 0; i < size(); i++) {
                pw.printf(Locale.ROOT, "%.3f,%.3f,%.3f,%.5f,%d%n", z[i], sigmaX[i], sigmaY[i], logRatio[i], beads[i]);
            }
        }
    }
}
