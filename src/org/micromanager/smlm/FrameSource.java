package org.micromanager.smlm;


public interface FrameSource {
    double[][] nextFrame(int frameIndex) throws Exception;
    int getWidth();
    int getHeight();
}
