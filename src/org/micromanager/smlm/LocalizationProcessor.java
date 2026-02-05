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

    public ProcessResult processImageForPreview(double[][] image,
                                                double bgSigma, double smoothSigma,
                                                int minPeakDistance, double peakThreshold,
                                                int roi) {
        int h = image.length;
        int w = image[0].length;

        // min-max normalize to 0..255
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

        // background blur
        FloatProcessor fpBg = new FloatProcessor(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) fpBg.setf(x, y, (float) norm[y][x]);
        GaussianBlur gb = new GaussianBlur();
        gb.blurFloat(fpBg, (float) bgSigma, (float) bgSigma, 0.02f);
        double[][] background = new double[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) background[y][x] = fpBg.getf(x, y);

        // bg subtract and small smoothing
        double[][] imgBg = new double[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) imgBg[y][x] = norm[y][x] - background[y][x];

        FloatProcessor fpSm = new FloatProcessor(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) fpSm.setf(x, y, (float) imgBg[y][x]);
        gb.blurFloat(fpSm, (float) smoothSigma, (float) smoothSigma, 0.02f);
        double[][] imgSm = new double[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) imgSm[y][x] = fpSm.getf(x, y);

        // detect peaks (NMS)
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

        // prepare display
        FloatProcessor displayFP = new FloatProcessor(w, h);
        double dmin = Double.POSITIVE_INFINITY, dmax = Double.NEGATIVE_INFINITY;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double v = imgSm[y][x];
            if (v < dmin) dmin = v;
            if (v > dmax) dmax = v;
        }
        double drange = Math.max(1e-6, dmax - dmin);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float val = (float) ((imgSm[y][x] - dmin) / drange * 255.0);
            displayFP.setf(x, y, val);
        }

        return new ProcessResult(displayFP, peaks, new ArrayList<>());
    }

    //  Acquisition processing (full localization) 
    public List<Localization> processImageForAcquisition(double[][] image, int frameNumber,
                                                        double backgroundSigma, double smoothingSigma,
                                                        int minPeakDistance, double peakThreshold,
                                                        int roiSize) {
        int h = image.length;
        int w = image[0].length;

        // min-max normalize to 0..255
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

        // background (large sigma)
        FloatProcessor fpBg = new FloatProcessor(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) fpBg.setf(x, y, (float) norm[y][x]);
        GaussianBlur gb = new GaussianBlur();
        gb.blurFloat(fpBg, (float) backgroundSigma, (float) backgroundSigma, 0.02f);
        double[][] background = new double[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) background[y][x] = fpBg.getf(x, y);

        // bg-subtract and small smoothing
        double[][] imgBg = new double[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) imgBg[y][x] = norm[y][x] - background[y][x];

        FloatProcessor fpSm = new FloatProcessor(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) fpSm.setf(x, y, (float) imgBg[y][x]);
        gb.blurFloat(fpSm, (float) smoothingSigma, (float) smoothingSigma, 0.02f);
        double[][] imgSm = new double[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) imgSm[y][x] = fpSm.getf(x, y);

        // detect peaks
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
                if (isMax) peaks.add(new int[]{x, y}); // store {x,y}
            }
        }

        // phasor localization
        List<Localization> locs = new ArrayList<>();
        int half = roiSize / 2;
        for (int[] p : peaks) {
            int px = p[0], py = p[1];
            if (py - half < 0 || (py + half + 1) > h || px - half < 0 || (px + half + 1) > w) continue;

            double[][] sub = new double[roiSize][roiSize];
            double maxVal = Double.NEGATIVE_INFINITY;
            double sum = 0.0;
            for (int ry = 0; ry < roiSize; ry++) {
                for (int rx = 0; rx < roiSize; rx++) {
                    double v = image[py - half + ry][px - half + rx];
                    sub[ry][rx] = v;
                    sum += v;
                    if (v > maxVal) maxVal = v;
                }
            }

            Complex[][] fft = Fft2D.fft2(sub);
            Complex cx = fft[0][1];
            Complex cy = fft[1][0];

            double angX = Math.atan2(cx.getImaginary(), cx.getReal());
            if (angX > 0) angX -= 2.0 * Math.PI;
            double angY = Math.atan2(cy.getImaginary(), cy.getReal());
            if (angY > 0) angY -= 2.0 * Math.PI;

            double posX = Math.abs(angX) / (2.0 * Math.PI / roiSize);
            double posY = Math.abs(angY) / (2.0 * Math.PI / roiSize);

            double globalX = px - half + posX;
            double globalY = py - half + posY;

            locs.add(new Localization(frameNumber, maxVal, globalX, globalY));
        }

        return locs;
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
