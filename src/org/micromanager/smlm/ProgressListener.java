package org.micromanager.smlm;

/**
 * Simple UI callback interface for showing acquisition progress/ETA.
 */
public interface ProgressListener {
    /**
     * Called once before acquisition begins (on worker thread).
     * @param totalFrames total number of frames expected (may be <=0 if unknown)
     */
    void initProgress(int totalFrames);

    /**
     * Called periodically during acquisition.
     * @param processed frames completed so far (0..totalFrames)
     * @param totalFrames total frames expected
     * @param etaText human-readable ETA string (e.g. "12s", "1m 03s")
     */
    void updateProgress(int processed, int totalFrames, String etaText);

    /**
     * Called when acquisition finishes or aborts (worker thread).
     */
    void closeProgress();
    
    /** Called immediately when the Camera loop finishes */
    void onCameraFinished();

    /** Updates the UI with the current size of the processing queue */
    void onQueueStatus(int pendingCount);
}
