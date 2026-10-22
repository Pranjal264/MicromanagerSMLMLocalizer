package org.micromanager.smlm;

import java.io.*;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;

/**
 * Small sidecar file written next to localizations.csv. The phasor width depends on the ROI size,
 * so the 3D tools use this to warn when a calibration and a data set were made with different ROIs.
 */
public final class AcquisitionInfo {
    public static final String FILE_NAME = "localizations_info.properties";

    private AcquisitionInfo() { }

    public static void write(File dir, AcquisitionParameters p) throws IOException {
        Properties pr = new Properties();
        pr.setProperty("pixelSizeNm", Double.toString(p.pixelSizeNm));
        pr.setProperty("roiSize", Integer.toString(p.roiSize));
        pr.setProperty("backgroundSigma", Double.toString(p.backgroundSigma));
        pr.setProperty("smoothingSigma", Double.toString(p.smoothingSigma));
        pr.setProperty("minPeakDistance", Integer.toString(p.minPeakDistance));
        pr.setProperty("peakThreshold", Double.toString(p.peakThreshold));
        pr.setProperty("frames", Integer.toString(p.frames));
        pr.setProperty("exposureMs", Integer.toString(p.exposureMs));
        try (OutputStream os = new FileOutputStream(new File(dir, FILE_NAME))) {
            pr.store(os, "SMLM localizer acquisition info, " + new Date());
        }
    }

    /** Read the sidecar that sits in the same folder as the given CSV; null if there is none. */
    public static Properties read(File csv) {
        if (csv == null || csv.getParentFile() == null) return null;
        File f = new File(csv.getParentFile(), FILE_NAME);
        if (!f.isFile()) return null;
        Properties pr = new Properties();
        try (InputStream is = new FileInputStream(f)) {
            pr.load(is);
            return pr;
        } catch (IOException e) {
            return null;
        }
    }

    public static int roiSize(Properties pr) {
        if (pr == null) return 0;
        try { return Integer.parseInt(pr.getProperty("roiSize", "0").trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    public static double pixelSizeNm(Properties pr) {
        if (pr == null) return 0;
        try { return Double.parseDouble(pr.getProperty("pixelSizeNm", "0").trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    public static String describe(Properties pr) {
        if (pr == null) return "no acquisition info found next to the CSV";
        return String.format(Locale.ROOT, "ROI %d px, pixel %.1f nm", roiSize(pr), pixelSizeNm(pr));
    }
}
