package org.micromanager.smlm;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.filter.GaussianBlur;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import org.apache.commons.math3.complex.Complex;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Contains image processing pipelines (preview and acquisition) and helpers to load simulation TIFFs.
 */
public class LocalizationProcessor {

    public LocalizationProcessor() {}

    public static class ProcessResult {
        public final FloatProcessor display;
        public final List<int[]> peaks;
        public final List<Localization> localizations;

        public ProcessResult(FloatProcessor display, List<int[]> peaks, List<Localization> localizations) {
            this.display = display;
            this.peaks = peaks;
            this.localizations = localizations;
        }
    }

    // ------------------------------------------------------------------
    //  Shared detection pipeline
    // ------------------------------------------------------------------

    /**
     * Min-max normalise to 0..255, subtract a heavily blurred copy (background) and apply a
     * light smoothing. The result is only used for peak detection / display; localization
     * itself always runs on the raw pixel values.
     */
    private double[][] prepareDetectionImage(double[][] image, double bgSigma, double smoothSigma) {
        int h = image.length;
        int w = image[0].length;

        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double v = image[y][x];
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double range = max - min;
        double[][] norm = new double[h][w];
        if (range == 0) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) norm[y][x] = image[y][x];
        } else {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) norm[y][x] = (image[y][x] - min) / range * 255.0;
        }

        FloatProcessor fpBg = new FloatProcessor(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) fpBg.setf(x, y, (float) norm[y][x]);
        GaussianBlur gb = new GaussianBlur();
        gb.blurFloat(fpBg, (float) bgSigma, (float) bgSigma, 0.02f);

        FloatProcessor fpSm = new FloatProcessor(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            fpSm.setf(x, y, (float) (norm[y][x] - fpBg.getf(x, y)));
        }
        gb.blurFloat(fpSm, (float) smoothSigma, (float) smoothSigma, 0.02f);

        double[][] imgSm = new double[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) imgSm[y][x] = fpSm.getf(x, y);
        return imgSm;
    }

    /** Non-maximum suppression: strict local maxima above threshold within the given radius. */
    private List<int[]> findPeaks(double[][] imgSm, int minPeakDistance, double peakThreshold) {
        int h = imgSm.length;
        int w = imgSm[0].length;
        List<int[]> peaks = new ArrayList<>();
        int radius = Math.max(1, minPeakDistance);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double v = imgSm[y][x];
                if (v < peakThreshold) continue;
                boolean isMax = true;
                for (int yy = Math.max(0, y - radius); yy <= Math.min(h - 1, y + radius) && isMax; yy++) {
                    for (int xx = Math.max(0, x - radius); xx <= Math.min(w - 1, x + radius); xx++) {
                        if (yy == y && xx == x) continue;
                        if (imgSm[yy][xx] >= v) { isMax = false; break; }
                    }
                }
                if (isMax) peaks.add(new int[]{x, y});
            }
        }
        return peaks;
    }

    // ------------------------------------------------------------------
    //  Preview (single frame, detection only)
    // ------------------------------------------------------------------

    public ProcessResult processImageForPreview(double[][] image,
                                                double bgSigma, double smoothSigma,
                                                int minPeakDistance, double peakThreshold,
                                                int roi) {
        int h = image.length;
        int w = image[0].length;

        double[][] imgSm = prepareDetectionImage(image, bgSigma, smoothSigma);
        List<int[]> peaks = findPeaks(imgSm, minPeakDistance, peakThreshold);

        FloatProcessor displayFP = new FloatProcessor(w, h);
        double dmin = Double.POSITIVE_INFINITY, dmax = Double.NEGATIVE_INFINITY;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double v = imgSm[y][x];
            if (v < dmin) dmin = v;
            if (v > dmax) dmax = v;
        }
        double drange = Math.max(1e-6, dmax - dmin);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            displayFP.setf(x, y, (float) ((imgSm[y][x] - dmin) / drange * 255.0));
        }
        return new ProcessResult(displayFP, peaks, new ArrayList<>());
    }

    // ------------------------------------------------------------------
    //  Acquisition processing (full localization)
    // ------------------------------------------------------------------

    public List<Localization> processImageForAcquisition(double[][] image, int frameNumber,
                                                        double backgroundSigma, double smoothingSigma,
                                                        int minPeakDistance, double peakThreshold,
                                                        int roiSize) {
        int h = image.length;
        int w = image[0].length;

        double[][] imgSm = prepareDetectionImage(image, backgroundSigma, smoothingSigma);
        List<int[]> peaks = findPeaks(imgSm, minPeakDistance, peakThreshold);

        List<Localization> locs = new ArrayList<>();
        int half = roiSize / 2;
        for (int[] p : peaks) {
            int px = p[0], py = p[1];
            if (py - half < 0 || (py + half + 1) > h || px - half < 0 || (px + half + 1) > w) continue;
            locs.add(localizePhasor(image, px, py, roiSize, frameNumber));
        }
        return locs;
    }

    /**
     * Phasor localization of one ROI centred on integer pixel (px, py).
     *
     * Position: phase of the first Fourier harmonic along each axis.
     * Width:    magnitude of the same harmonic relative to DC. For a Gaussian of std-dev
     *           sigma (pixels) sampled over N pixels,
     *               |F1| / F0 = exp(-2 pi^2 sigma^2 / N^2)
     *           so  sigma = N * sqrt(-ln(|F1|/F0)) / (pi * sqrt(2)).
     *
     * The local background is removed first, estimated as the median of a ring of pixels
     * lying RING_GAP pixels outside the ROI. It must not come from inside the ROI: when the PSF
     * reaches the ROI edge, an in-ROI estimate eats real signal and biases the width low by up
     * to ~40%. A constant offset does not change the phase, so positions are unaffected by this
     * step; the width is, because the offset inflates DC.
     *
     * The width is the "phasor width" of the ROI, which also depends on ROI size and pixel
     * integration. That is fine for astigmatism, because the z calibration is done with the
     * same estimator, but do not read it as a calibrated physical sigma.
     */
    private Localization localizePhasor(double[][] image, int px, int py, int roiSize, int frameNumber) {
        final int half = roiSize / 2;
        double[][] sub = new double[roiSize][roiSize];
        double maxVal = Double.NEGATIVE_INFINITY;
        for (int ry = 0; ry < roiSize; ry++) {
            for (int rx = 0; rx < roiSize; rx++) {
                double v = image[py - half + ry][px - half + rx];
                sub[ry][rx] = v;
                if (v > maxVal) maxVal = v;
            }
        }
        double bg = estimateBackground(image, px, py, half + RING_GAP);
        for (int ry = 0; ry < roiSize; ry++) for (int rx = 0; rx < roiSize; rx++) sub[ry][rx] -= bg;

        Complex[][] fft = Fft2D.fft2(sub);
        Complex dc = fft[0][0];
        Complex cx = fft[0][1];   // first harmonic along x
        Complex cy = fft[1][0];   // first harmonic along y

        double angX = Math.atan2(cx.getImaginary(), cx.getReal());
        if (angX > 0) angX -= 2.0 * Math.PI;
        double angY = Math.atan2(cy.getImaginary(), cy.getReal());
        if (angY > 0) angY -= 2.0 * Math.PI;

        double posX = Math.abs(angX) / (2.0 * Math.PI / roiSize);
        double posY = Math.abs(angY) / (2.0 * Math.PI / roiSize);

        double sigmaX = sigmaFromPhasor(cx.abs(), dc.getReal(), roiSize);
        double sigmaY = sigmaFromPhasor(cy.abs(), dc.getReal(), roiSize);

        return new Localization(frameNumber, maxVal, px - half + posX, py - half + posY, sigmaX, sigmaY);
    }

    /** Distance (pixels) between the ROI edge and the ring used for the background estimate. */
    private static final int RING_GAP = 3;

    /**
     * Median of the pixels on the square ring at Chebyshev distance 'radius' from (cx, cy),
     * clipped to the image. The median keeps a neighbouring emitter that touches a few ring
     * pixels from skewing the estimate.
     */
    private static double estimateBackground(double[][] image, int cx, int cy, int radius) {
        final int h = image.length, w = image[0].length;
        double[] vals = new double[8 * radius];
        int n = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            int y = cy + dy;
            if (y < 0 || y >= h) continue;
            boolean edgeRow = (dy == -radius || dy == radius);
            int step = edgeRow ? 1 : 2 * radius;          // full row on top/bottom, two end pixels otherwise
            for (int dx = -radius; dx <= radius; dx += step) {
                int x = cx + dx;
                if (x < 0 || x >= w) continue;
                vals[n++] = image[y][x];
            }
        }
        if (n == 0) return image[cy][cx];
        Arrays.sort(vals, 0, n);
        return (n % 2 == 1) ? vals[n / 2] : 0.5 * (vals[n / 2 - 1] + vals[n / 2]);
    }

    /** Gaussian width (pixels) from first-harmonic magnitude and DC; NaN if not computable. */
    static double sigmaFromPhasor(double magnitude, double dc, int roiSize) {
        if (!(dc > 0)) return Double.NaN;
        double m = magnitude / dc;
        if (!(m > 0.0 && m < 1.0)) return Double.NaN;
        return roiSize * Math.sqrt(-Math.log(m)) / (Math.PI * Math.sqrt(2.0));
    }

    //  helper methods for simulation file listing and loading 
    public String[] getTiffFileList(String folder) {
        try {
            File dir = new File(folder);
            File[] files = dir.listFiles((d, name) -> {
                String n = name.toLowerCase();
                return n.endsWith(".tif") || n.endsWith(".tiff");
            });
            if (files == null || files.length == 0) return new String[0];
            Arrays.sort(files, Comparator.comparing(File::getName));
            String[] out = new String[files.length];
            for (int i = 0; i < files.length; i++) out[i] = files[i].getAbsolutePath();
            return out;
        } catch (Exception e) {
            return new String[0];
        }
    }

    public double[][] loadSimImageFromPath(String fullPath) throws Exception {
        ImagePlus imp = IJ.openImage(fullPath);
        if (imp == null) throw new Exception("Cannot open image: " + fullPath);
        ImageProcessor ip = imp.getProcessor().convertToFloatProcessor();
        int w = ip.getWidth();
        int h = ip.getHeight();
        double[][] image = new double[h][w];
        float[] pix = (float[]) ip.getPixels();
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                image[y][x] = pix[row + x];
            }
        }
        return image;
    }
}
