package org.micromanager.smlm;

import org.apache.commons.math3.complex.Complex;
import org.jtransforms.fft.DoubleFFT_2D;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class Fft2D {

    private static final Map<String, DoubleFFT_2D> FFT_CACHE = new ConcurrentHashMap<>();

    private static final Map<String, ThreadLocal<double[][]>> BUFFER_CACHE = new ConcurrentHashMap<>();

    // Threshold below which we prefer the direct DFT (keeps behavior identical to your dft2)
    // You can set this to 0 to always use JTransforms.
    private static final int SMALL_DFT_MAX = 9; // for ROI <= 9x9 use direct dft (fast enough and robust)

    private static String keyFor(int h, int w) {
        return h + "x" + w;
    }

    private static DoubleFFT_2D getOrCreateFFT(int h, int w) {
        String key = keyFor(h, w);
        return FFT_CACHE.computeIfAbsent(key, k -> new DoubleFFT_2D(h, w));
    }

    private static double[][] getOrCreateBuffer(int h, int w) {
        String key = keyFor(h, w);
        ThreadLocal<double[][]> tl = BUFFER_CACHE.computeIfAbsent(key, k ->
                ThreadLocal.withInitial(() -> new double[h][2 * w])
        );
        double[][] buf = tl.get();
        // defensive check: sometimes JDK may reuse thread locals across different sizes in unusual setups;
        // if dimensions mismatch, create a fresh buffer and replace the ThreadLocal for safety.
        if (buf.length != h || (buf.length > 0 && buf[0].length != 2 * w)) {
            double[][] n = new double[h][2 * w];
            tl.set(n);
            return n;
        }
        return buf;
    }

    /**
     * Compute 2D FFT of a real-valued input matrix.
     *
     * @param input real-valued input [h][w] (row-major, image[y][x])
     * @return complex-valued output [h][w] (Complex objects) where out[u][v] corresponds to frequency (u,v)
     */
    public static Complex[][] fft2(double[][] input) {
        final int h = input.length;
        if (h == 0) return new Complex[0][0];
        final int w = input[0].length;

        // Optionally use direct DFT for very small ROIs (matches your correct dft2)
        if (Math.max(h, w) <= SMALL_DFT_MAX && h == w) {
            return dft2(input);
        }

        // Get reusable buffer and FFT object
        double[][] data = getOrCreateBuffer(h, w); // shape [h][2*w]
        DoubleFFT_2D fft = getOrCreateFFT(h, w);

        // Copy input into interleaved complex buffer (real, imag)
        // We must zero the imag components; copying both real and zeroing imag in one loop is simplest
        for (int i = 0; i < h; i++) {
            double[] rowBuf = data[i];
            // assume rowBuf.length == 2*w
            for (int j = 0, k = 0; j < w; j++, k += 2) {
                rowBuf[k] = input[i][j];
                rowBuf[k + 1] = 0.0;
            }
        }

        // In-place forward complex FFT with standard sign convention (matches direct DFT implementation)
        fft.complexForward(data);

        // Convert to Complex[h][w] output
        Complex[][] out = new Complex[h][w];
        for (int i = 0; i < h; i++) {
            double[] rowBuf = data[i];
            for (int j = 0, k = 0; j < w; j++, k += 2) {
                double re = rowBuf[k];
                double im = rowBuf[k + 1];
                out[i][j] = new Complex(re, im);
            }
        }
        return out;
    }

    /**
     * Direct (naive) DFT implementation for square ROIs.
     * This uses the convention:
     *   F[u][v] = sum_{y=0..N-1} sum_{x=0..N-1} ROI[y][x] * exp(-2*pi*i*(u*y/N + v*x/N))
     * Use this for correctness testing or for very small ROI sizes.
     */
    public static Complex[][] dft2(double[][] roi) {
        int N = roi.length;
        Complex[][] F = new Complex[N][N];
        double twoPiDivN = 2.0 * Math.PI / N;
        for (int u = 0; u < N; u++) {
            for (int v = 0; v < N; v++) {
                double re = 0.0, im = 0.0;
                for (int y = 0; y < N; y++) {
                    for (int x = 0; x < N; x++) {
                        double val = roi[y][x];
                        double angle = -twoPiDivN * (u * y + v * x);
                        double c = Math.cos(angle);
                        double s = Math.sin(angle);
                        re += val * c;
                        im += val * s;
                    }
                }
                F[u][v] = new Complex(re, im);
            }
        }
        return F;
    }
}
