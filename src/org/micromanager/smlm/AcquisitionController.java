package org.micromanager.smlm;

import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.micromanager.Studio;
import mmcorej.TaggedImage;

import javax.swing.*;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AcquisitionController 
 */
public class AcquisitionController {

    private final Studio studio;
    private final LocalizationProcessor processor;
    private final LocalizationAccumulator accumulator;

    private volatile boolean stopRequested = false;
    private Thread acqThread = null;
    private Thread processThread = null;

    private ImagePlus livePreviewImp = null;
    private final Object livePreviewLock = new Object();
    private volatile ProgressListener progressListener = null;

    private long acqStartTimeNs = 0L;
    private double avgFrameMs = 0.0;
    private final double ALPHA = 0.12;

    private static class AcqFrame {
        final Object rawPixels;
        final int width;
        final int height;
        final int frameIndex;
        final String savedFilename;

        AcqFrame(Object rawPixels, int width, int height, int frameIndex, String savedFilename) {
            this.rawPixels = rawPixels;
            this.width = width;
            this.height = height;
            this.frameIndex = frameIndex;
            this.savedFilename = savedFilename;
        }
    }

    public AcquisitionController(Studio studio,
                                 LocalizationProcessor processor,
                                 LocalizationAccumulator accumulator) {
        this.studio = studio;
        this.processor = processor;
        this.accumulator = accumulator;
    }

    public void setProgressListener(ProgressListener l) { this.progressListener = l; }

    //Preview (Single Frame - Uses Snap)
    public void preview(AcquisitionParameters params) {
        new Thread(() -> {
            try {
                double[][] image;
                if (params.simulationMode) {
                    if (params.simFolder == null) { showError("Simulation folder not set"); return; }
                    String[] list = processor.getTiffFileList(params.simFolder);
                    if (list.length == 0) { showError("No TIFF files found"); return; }
                    image = processor.loadSimImageFromPath(list[0]);
                } else {
                    if (studio == null) { showError("Studio not available"); return; }
                    studio.core().snapImage();
                    image = toDoubleArray(studio.core().getImage(), (int)studio.core().getImageWidth(), (int)studio.core().getImageHeight());
                }

                LocalizationProcessor.ProcessResult pr = processor.processImageForPreview(image,
                        params.backgroundSigma, params.smoothingSigma,
                        params.minPeakDistance, params.peakThreshold, params.roiSize);

                FloatProcessor rawDisplay = createDisplayFromRaw(image);
                ImagePlus previewImp = new ImagePlus("Preview", rawDisplay);
                Overlay ov = new Overlay();
                int r = Math.max(1, params.roiSize / 2);
                for (int[] pxy : pr.peaks) {
                    OvalRoi or = new OvalRoi(pxy[0] - r, pxy[1] - r, 2 * r + 1, 2 * r + 1);
                    or.setStrokeColor(java.awt.Color.RED);
                    ov.add(or);
                }
                previewImp.setOverlay(ov);
                SwingUtilities.invokeLater(previewImp::show);

            } catch (Exception ex) {
                showError("Preview error: " + ex.getMessage());
            }
        }, "SMLM-Preview-Thread").start();
    }

    public void startAcquisition(AcquisitionParameters params) {
        stopRequested = false;
        acqThread = new Thread(() -> runProducerConsumerAcquisition(params), "SMLM-Acq-Thread");
        acqThread.setDaemon(true);
        acqThread.start();
    }

    public void requestStop() {
        stopRequested = true;
        closeLivePreview();
        try { if (studio != null) studio.core().stopSequenceAcquisition(); } catch (Exception ignored) {}
        if (acqThread != null) try { acqThread.join(200); } catch (Exception ignored) {}
        if (processThread != null) try { processThread.join(200); } catch (Exception ignored) {}
    }

    private void runProducerConsumerAcquisition(AcquisitionParameters params) {
        // Queue size 100 is good for 512x512. Decrease if full chip.
        BlockingQueue<AcqFrame> processingQueue = new LinkedBlockingQueue<>(100);
        
        List<LocalizationCsvRow> csvRows = new ArrayList<>();
        AtomicInteger producerStatus = new AtomicInteger(0);

        processThread = new Thread(() -> 
            runProcessingLoop(processingQueue, csvRows, params, producerStatus), 
            "SMLM-Proc-Thread"
        );
        processThread.start();

        runAcquisitionLoop(processingQueue, params, producerStatus);

        try { processThread.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finalizeAcquisition(csvRows, params);
    }

    /**
     * High-Speed Sequence Acquisition
     */
    private void runAcquisitionLoop(BlockingQueue<AcqFrame> queue, 
                                    AcquisitionParameters params, 
                                    AtomicInteger status) {
        ImageStack stack = null;
        boolean stackingEnabled = false;
        boolean wantMultipage = params.saveMultipage;
        boolean wantPerFrame = params.saveSingleFiles;
        
        if (params.saveStack && params.outDir != null) {
            File d = new File(params.outDir);
            if (!d.exists() && !d.mkdirs()) {
                showError("Failed to create output directory");
                status.set(2); return;
            }
            if (wantMultipage && !wantPerFrame) stackingEnabled = true;
        }

        String[] simFiles = null;
        if (params.simulationMode) {
            simFiles = processor.getTiffFileList(params.simFolder);
            if (simFiles.length == 0) { showError("No simulation files"); status.set(2); return; }
        }

        if (progressListener != null) progressListener.initProgress(params.frames);
        acqStartTimeNs = System.nanoTime();

        try {
            //  START HARDWARE SEQUENCE 
            if (!params.simulationMode && studio != null) {
                studio.core().setExposure(params.exposureMs);
                // Start sequence: frames, delay=0, stopOnOverflow=true
                studio.core().startSequenceAcquisition(params.frames, 0, true);
            }

            int currentFrame = 0;
            int w = 0, h = 0;

            // Loop until we have processed all frames or stopped
            while (currentFrame < params.frames && !stopRequested) {
                
                Object rawPix = null;
                boolean gotImage = false;

                if (params.simulationMode) {
                    // Simulation: Just load file
                    double[][] dImg = processor.loadSimImageFromPath(simFiles[currentFrame % simFiles.length]);
                    rawPix = dImg;
                    h = dImg.length; w = dImg[0].length;
                    gotImage = true;
                } else {
                    // Hardware: Pull from Circular Buffer
                    // Check if camera has pushed an image
                    if (studio.core().getRemainingImageCount() > 0) {
                        TaggedImage tImg = studio.core().popNextTaggedImage();
                        rawPix = tImg.pix;
                        w = tImg.tags.getInt("Width");
                        h = tImg.tags.getInt("Height");
                        gotImage = true;
                    } else {
                        // If sequence is finished but buffer empty, we are done
                        if (!studio.core().isSequenceRunning()) break;
                        // Otherwise wait slightly for camera
                        Thread.sleep(1); 
                    }
                }

                if (gotImage) {
                    String savedFilename = "";

                    // SAVE to RAM Stack or Disk 
                    if (stack == null && stackingEnabled) stack = new ImageStack(w, h);

                    if (params.saveStack && params.outDir != null) {
                        if (stackingEnabled && stack != null) {
                            addRawToStack(stack, rawPix, w, h);
                            savedFilename = "frames_stack.tif";
                        } else {
                            // WARNING: Saving single files to disk is slow!
                            savedFilename = String.format("frame_%06d.tif", currentFrame);
                            saveSingleTiff(rawPix, w, h, new File(params.outDir, savedFilename).getAbsolutePath());
                        }
                    }

                    // QUEUE 
                    // If queue is full, returns false (drops frame from processing, but saved above!)
                    boolean queued = queue.offer(new AcqFrame(rawPix, w, h, currentFrame + 1, savedFilename));

                    currentFrame++;
                    updateTimingAndNotifyProgress(currentFrame, params.frames);
                }
            }

            // Stop hardware if still running
            if (!params.simulationMode && studio != null && studio.core().isSequenceRunning()) {
                studio.core().stopSequenceAcquisition();
            }

            // Write Stack to Disk
            if (stack != null && params.outDir != null) {
                ImagePlus sImp = new ImagePlus("stack", stack);
                FileSaver fs = new FileSaver(sImp);
                String stackPath = new File(params.outDir, "frames_stack.tif").getAbsolutePath();
                fs.saveAsTiffStack(stackPath);
            }

            if (progressListener != null) progressListener.onCameraFinished();
            status.set(1);

        } catch (Exception e) {
            showError("Acquisition Error: " + e.getMessage());
            try { if (studio != null) studio.core().stopSequenceAcquisition(); } catch (Exception ignored) {}
            status.set(2);
        }
    }

    private void runProcessingLoop(BlockingQueue<AcqFrame> queue, 
                                   List<LocalizationCsvRow> csvRows,
                                   AcquisitionParameters params, 
                                   AtomicInteger producerStatus) {
        
        while (producerStatus.get() == 0 || !queue.isEmpty()) {
            if (stopRequested && queue.isEmpty()) break;
            if (progressListener != null) progressListener.onQueueStatus(queue.size());

            try {
                AcqFrame frame = queue.poll(100, TimeUnit.MILLISECONDS);
                if (frame == null) continue;

                // CONVERT
                double[][] image;
                if (frame.rawPixels instanceof double[][]) {
                    image = (double[][]) frame.rawPixels;
                } else {
                    image = toDoubleArray(frame.rawPixels, frame.width, frame.height);
                }

                // LOCALIZE
                List<Localization> locs = processor.processImageForAcquisition(
                        image, frame.frameIndex,
                        params.backgroundSigma, params.smoothingSigma,
                        params.minPeakDistance, params.peakThreshold, params.roiSize
                );

                // ACCUMULATE
                accumulator.initIfNeeded(frame.width, frame.height, params.mag);
                accumulator.addLocalizations(locs);

                // STORE
                for (Localization loc : locs) {
                    csvRows.add(new LocalizationCsvRow(loc.frame, loc.amplitude, loc.x, loc.y, frame.savedFilename));
                }

                // PREVIEW
                if (params.showLivePreview) {
                    FloatProcessor rawDisplay = createDisplayFromRaw(image);
                    List<int[]> peaks = new ArrayList<>();
                    for(Localization L : locs) peaks.add(new int[]{(int)L.x, (int)L.y});
                    showOrUpdateLivePreview(rawDisplay, peaks, params.roiSize);
                }

                if (frame.frameIndex % params.updateInterval == 0) accumulator.updateDisplay();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                System.err.println("Processing error: " + ex.getMessage());
            }
        }
        accumulator.updateDisplay();
    }

    private void finalizeAcquisition(List<LocalizationCsvRow> csvRows, AcquisitionParameters params) {
        if (progressListener != null) progressListener.closeProgress();
        closeLivePreview();

        if (params.outDir != null) {
            File csvFile = new File(params.outDir, "localizations.csv");
            try (PrintWriter pw = new PrintWriter(new FileWriter(csvFile))) {
                pw.println("Frame,Amplitude,X,Y,Filename");
                for (LocalizationCsvRow row : csvRows) {
                    pw.printf("%d,%.6f,%.6f,%.6f,%s%n", row.frame, row.amp, row.x, row.y, row.filename);
                }
                if (studio != null) studio.logs().showMessage("Saved " + csvRows.size() + " localizations.");
            } catch (Exception ex) {
                showError("Failed to write CSV: " + ex.getMessage());
            }
        }
        if (studio != null) studio.alerts().postAlert("SMLM Finished", this.getClass(), "Localizations: " + csvRows.size());
    }

    // Helpers 
    
    private void addRawToStack(ImageStack stack, Object rawPix, int w, int h) {
        short[] sPix;
        if (rawPix instanceof short[]) {
            short[] src = (short[]) rawPix;
            sPix = new short[w*h];
            int min = 65535, max = 0;
            for(short s : src) {
                int v = s & 0xffff;
                if(v < min) min = v;
                if(v > max) max = v;
            }
            double range = max - min;
            if(range <= 0) range = 1.0;
            for(int i=0; i<src.length; i++) {
                int val = (int) (((src[i] & 0xffff) - min) / range * 65535.0);
                sPix[i] = (short)val;
            }
        } else if (rawPix instanceof byte[]) {
            byte[] src = (byte[]) rawPix;
            sPix = new short[w*h];
            for(int i=0; i<src.length; i++) sPix[i] = (short) ((src[i] & 0xff) * 257); 
        } else if (rawPix instanceof double[][]) {
             double[][] src = (double[][]) rawPix;
             sPix = new short[w*h];
             double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
             for(double[] r : src) for(double v : r) { if(v<min)min=v; if(v>max)max=v; }
             double range = max - min; if(range<=0) range=1;
             int idx=0;
             for(int y=0; y<h; y++) for(int x=0; x<w; x++) {
                 int val = (int)((src[y][x] - min)/range * 65535.0);
                 sPix[idx++] = (short)val;
             }
        } else {
            sPix = new short[w*h];
        }
        stack.addSlice(new ShortProcessor(w, h, sPix, null));
    }

    private void saveSingleTiff(Object rawPix, int w, int h, String path) {
        ImageStack tmp = new ImageStack(w, h);
        addRawToStack(tmp, rawPix, w, h);
        new FileSaver(new ImagePlus("", tmp.getProcessor(1))).saveAsTiff(path);
    }

    private double[][] toDoubleArray(Object pix, int w, int h) {
        double[][] image = new double[h][w];
        if (pix instanceof byte[]) {
            byte[] b = (byte[]) pix;
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) image[y][x] = (b[row + x] & 0xff);
            }
        } else if (pix instanceof short[]) {
            short[] s = (short[]) pix;
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) image[y][x] = (s[row + x] & 0xffff);
            }
        }
        return image;
    }

    private static FloatProcessor createDisplayFromRaw(double[][] image) {
        int h = image.length; int w = image[0].length;
        FloatProcessor fp = new FloatProcessor(w, h);
        double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
        for (double[] r : image) for (double v : r) {
            if (v < mn) mn = v; if (v > mx) mx = v;
        }
        double range = mx - mn; if (range==0) range=1;
        for(int y=0; y<h; y++) for(int x=0; x<w; x++) {
            fp.setf(x, y, (float)((image[y][x]-mn)/range * 255.0));
        }
        return fp;
    }
    
    private void showOrUpdateLivePreview(final FloatProcessor rawDisplay, List<int[]> peaks, int roiSize) {
        synchronized (livePreviewLock) {
            SwingUtilities.invokeLater(() -> {
                if (livePreviewImp == null || livePreviewImp.getWindow() == null) {
                    livePreviewImp = new ImagePlus("Live preview", rawDisplay);
                    livePreviewImp.show();
                } else {
                    livePreviewImp.setProcessor(rawDisplay);
                }
                Overlay ov = new Overlay();
                int r = Math.max(1, roiSize / 2);
                for (int[] p : peaks) {
                    OvalRoi or = new OvalRoi(p[0] - r, p[1] - r, 2 * r + 1, 2 * r + 1);
                    or.setStrokeColor(java.awt.Color.RED);
                    ov.add(or);
                }
                livePreviewImp.setOverlay(ov);
            });
        }
    }

    private void closeLivePreview() {
        synchronized (livePreviewLock) {
            if (livePreviewImp != null) {
                ImagePlus imp = livePreviewImp; 
                SwingUtilities.invokeLater(() -> {
                    if (imp.getWindow() != null) imp.getWindow().close();
                });
                livePreviewImp = null;
            }
        }
    }
    
    private void updateTimingAndNotifyProgress(int processed, int frames) {
         long now = System.nanoTime();
         double elapsedMs = (now - acqStartTimeNs) / 1e6;
         double instFrameMs = (processed > 0) ? (elapsedMs / processed) : 0.0;
         if (avgFrameMs == 0.0) avgFrameMs = instFrameMs;
         else avgFrameMs = ALPHA * instFrameMs + (1.0 - ALPHA) * avgFrameMs;
         long remaining = Math.max(0, frames - processed);
         long etaMs = Math.round(remaining * avgFrameMs);
         if (progressListener != null) {
             String etaStr = String.format("%ds", etaMs/1000);
             progressListener.updateProgress(processed, frames, etaStr);
         }
    }

    private void showError(String msg) {
        if (studio != null) studio.logs().showError(msg);
        else System.err.println("Error: " + msg);
    }

    private static class LocalizationCsvRow {
        int frame; double amp, x, y; String filename;
        LocalizationCsvRow(int frame, double amp, double x, double y, String filename) {
            this.frame = frame; this.amp = amp; this.x = x; this.y = y; this.filename = filename;
        }
    }
}