package org.micromanager.smlm;

public class AcquisitionParameters {
    public int frames;
    public int exposureMs;
    public int mag;
    public int updateInterval;
    public double backgroundSigma;
    public double smoothingSigma;
    public int minPeakDistance;
    public double peakThreshold;
    public int roiSize;
    public boolean saveStack;
    public String outDir;
    public boolean simulationMode;
    public String simFolder;
    public boolean saveSingleFiles = false;      
    public boolean saveMultipage = true;         
    public boolean showLivePreview = true;       
}