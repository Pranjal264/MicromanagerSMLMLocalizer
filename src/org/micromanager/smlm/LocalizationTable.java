package org.micromanager.smlm;

import java.io.*;
import java.util.Arrays;
import java.util.Locale;

/**
 * Column store for a localization CSV (all lengths in nm). z is null until a calibration has been applied.
 * Reads the CSV written by the acquisition (Frame,X_nm,Y_nm,SigmaX_nm,SigmaY_nm,Amplitude,Filename)
 * and also the 3D version that has an additional Z_nm column. Columns are found by name.
 */
public final class LocalizationTable {
    public final int n;
    public final int[] frame;
    public final double[] x, y, sigmaX, sigmaY, amplitude;
    public final String[] filename;
    public final double[] z;          // nm, or null

    public LocalizationTable(int[] frame, double[] x, double[] y, double[] z,
                             double[] sigmaX, double[] sigmaY, double[] amplitude, String[] filename) {
        this.n = frame.length;
        this.frame = frame; this.x = x; this.y = y; this.z = z;
        this.sigmaX = sigmaX; this.sigmaY = sigmaY; this.amplitude = amplitude; this.filename = filename;
    }

    public LocalizationTable withZ(double[] newZ) {
        return new LocalizationTable(frame, x, y, newZ, sigmaX, sigmaY, amplitude, filename);
    }

    /** New table with only the rows where keep[i] is true. */
    public LocalizationTable select(boolean[] keep) {
        int m = 0;
        for (boolean b : keep) if (b) m++;
        int[] f = new int[m];
        double[] xx = new double[m], yy = new double[m], sx = new double[m], sy = new double[m], am = new double[m];
        double[] zz = (z == null) ? null : new double[m];
        String[] fn = new String[m];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            f[k] = frame[i]; xx[k] = x[i]; yy[k] = y[i]; sx[k] = sigmaX[i]; sy[k] = sigmaY[i];
            am[k] = amplitude[i]; fn[k] = filename[i];
            if (zz != null) zz[k] = z[i];
            k++;
        }
        return new LocalizationTable(f, xx, yy, zz, sx, sy, am, fn);
    }

    // ------------------------------------------------------------------ IO

    private static final class DArr {
        double[] a = new double[1024]; int n = 0;
        void add(double v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
        double[] done() { return Arrays.copyOf(a, n); }
    }
    private static final class IArr {
        int[] a = new int[1024]; int n = 0;
        void add(int v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
        int[] done() { return Arrays.copyOf(a, n); }
    }

    private static String norm(String h) {
        return h.trim().toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
    }

    public static LocalizationTable load(File file) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String header = br.readLine();
            if (header == null) throw new IOException("Empty file: " + file.getName());
            String[] h = header.split(",", -1);
            int cF = -1, cX = -1, cY = -1, cZ = -1, cSx = -1, cSy = -1, cA = -1, cFn = -1;
            for (int i = 0; i < h.length; i++) {
                switch (norm(h[i])) {
                    case "frame": cF = i; break;
                    case "xnm": case "x[nm]": cX = i; break;
                    case "ynm": case "y[nm]": cY = i; break;
                    case "znm": case "z[nm]": cZ = i; break;
                    case "sigmaxnm": case "sigma1[nm]": case "sigmax[nm]": cSx = i; break;
                    case "sigmaynm": case "sigma2[nm]": case "sigmay[nm]": cSy = i; break;
                    case "amplitude": case "intensity[photon]": case "intensity": cA = i; break;
                    case "filename": cFn = i; break;
                    case "id": break;
                    default: break;
                }
            }
            if (cF < 0 || cX < 0 || cY < 0 || cSx < 0 || cSy < 0) {
                throw new IOException("Not a localization CSV with widths. Expected columns "
                        + "Frame, X_nm, Y_nm, SigmaX_nm, SigmaY_nm (found: " + header + ")");
            }
            IArr fr = new IArr();
            DArr xs = new DArr(), ys = new DArr(), zs = new DArr(), sxs = new DArr(), sys = new DArr(), am = new DArr();
            java.util.ArrayList<String> fns = new java.util.ArrayList<>();
            String line; int lineNo = 1;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isEmpty()) continue;
                String[] c = line.split(",", -1);
                try {
                    fr.add(Integer.parseInt(c[cF].trim()));
                    xs.add(Double.parseDouble(c[cX].trim()));
                    ys.add(Double.parseDouble(c[cY].trim()));
                    sxs.add(Double.parseDouble(c[cSx].trim()));
                    sys.add(Double.parseDouble(c[cSy].trim()));
                    am.add(cA >= 0 ? Double.parseDouble(c[cA].trim()) : Double.NaN);
                    if (cZ >= 0) zs.add(Double.parseDouble(c[cZ].trim()));
                    fns.add(cFn >= 0 && cFn < c.length ? c[cFn] : "");
                } catch (RuntimeException e) {
                    throw new IOException("Cannot parse line " + lineNo + " of " + file.getName() + ": " + line);
                }
            }
            return new LocalizationTable(fr.done(), xs.done(), ys.done(), cZ >= 0 ? zs.done() : null,
                    sxs.done(), sys.done(), am.done(), fns.toArray(new String[0]));
        }
    }

    public void writeCsv(File file) throws IOException {
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(file)))) {
            if (z != null) pw.println("id,frame,x [nm],y [nm],z [nm],sigma1 [nm],sigma2 [nm],intensity [photon]");
            else pw.println("id,frame,x [nm],y [nm],sigma1 [nm],sigma2 [nm],intensity [photon]");
            for (int i = 0; i < n; i++) {
                if (z != null) {
                    pw.printf(Locale.ROOT, "%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                            i + 1, frame[i], x[i], y[i], z[i], sigmaX[i], sigmaY[i], amplitude[i]);
                } else {
                    pw.printf(Locale.ROOT, "%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                            i + 1, frame[i], x[i], y[i], sigmaX[i], sigmaY[i], amplitude[i]);
                }
            }
        }
    }
}
