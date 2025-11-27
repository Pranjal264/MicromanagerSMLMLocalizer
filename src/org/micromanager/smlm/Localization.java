package org.micromanager.smlm;

public final class Localization {
    public final double amplitude;
    public final double x;
    public final double y;
    public final int frame;

    public Localization(int frame, double amplitude, double x, double y) {
        this.frame = frame;
        this.amplitude = amplitude;
        this.x = x;
        this.y = y;
    }
}
