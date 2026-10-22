package org.micromanager.smlm;

public class AcquisitionParameters {
    // --- Part 1: acquisition & localization ---
    public int frames;
    public int exposureMs;
    public double pixelSizeNm = 100.0;      // camera pixel size at the sample plane
    public double backgroundSigma;
    public double smoothingSigma;
    public int minPeakDistance;
    public double peakThreshold;
    public int roiSize;
    public boolean simulationMode;
    public String simFolder;
    public boolean showLivePreview = true;

    // --- Part 2: output ---
    public String outDir;
    public boolean saveStack;                // raw acquired frames
    public boolean saveSingleFiles = false;  // stack as one TIFF per frame
    public boolean saveMultipage = true;     // stack as one multi-page TIFF
    public boolean saveReconstruction = false; // reconstructed (histogram) image
    public boolean saveCsv = true;           // localization table (nm)
    public int mag;                          // reconstruction magnification
    public int updateInterval;               // refresh reconstruction every N frames
}
