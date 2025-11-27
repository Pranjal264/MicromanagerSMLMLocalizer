package org.micromanager.smlm;



public class SimulationFrameSource implements FrameSource {
    private final String[] files;
    private int width = -1;
    private int height = -1;

    public SimulationFrameSource(String[] files) {
        this.files = files;
    }

    @Override
    public double[][] nextFrame(int frameIndex) throws Exception {
        String path = files[frameIndex % files.length];
        return new LocalizationProcessor().loadSimImageFromPath(path);
    }

    @Override
    public int getWidth() { return width; }

    @Override
    public int getHeight() { return height; }
}
