package org.micromanager.smlm;

import org.micromanager.MenuPlugin;
import org.micromanager.Studio;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.SciJavaPlugin;

import javax.swing.*;

/**
 * Entrypoint for Micro-Manager. 
 * This class wires the UI, acquisition controller and the processor.
 */
@Plugin(type = MenuPlugin.class)
public class SMLMLocalizer implements MenuPlugin, SciJavaPlugin {

    private Studio studio_;

    // the UI and controllers
    private SMLMLocalizerUI ui_;
    private AcquisitionController acqController_;

    public SMLMLocalizer() {
        // nothing here; Studio context arrives in setContext
    }

    @Override
    public void setContext(Studio studio) {
        this.studio_ = studio;
        // Create processor & accumulator
        LocalizationProcessor processor = new LocalizationProcessor();
        LocalizationAccumulator accumulator = new LocalizationAccumulator();
        // create UI (UI doesn't need Studio) first, so we can wire progress listener
        this.ui_ = new SMLMLocalizerUI();
        // create controller with Studio and wire UI as progress listener
        this.acqController_ = new AcquisitionController(studio_, processor, accumulator);
        this.acqController_.setProgressListener(ui_);
        // wire callbacks
        ui_.setPreviewCallback(params -> acqController_.preview(params));
        ui_.setStartCallback(params -> acqController_.startAcquisition(params));
        ui_.setStopCallback(() -> acqController_.requestStop());
        ui_.setClearCallback(() -> accumulator.clear());
    }

    @Override
    public void onPluginSelected() {
        // show UI on EDT
        SwingUtilities.invokeLater(() -> {
            if (ui_ == null) {
                // in case setContext not called (local run), create defaults
                LocalizationProcessor processor = new LocalizationProcessor();
                LocalizationAccumulator accumulator = new LocalizationAccumulator();
                this.acqController_ = new AcquisitionController(null, processor, accumulator);
                this.ui_ = new SMLMLocalizerUI();
                this.acqController_.setProgressListener(ui_);
                ui_.setPreviewCallback(params -> acqController_.preview(params));
                ui_.setStartCallback(params -> acqController_.startAcquisition(params));
                ui_.setStopCallback(() -> acqController_.requestStop());
                ui_.setClearCallback(() -> accumulator.clear());
            }
            ui_.show();
        });
    }

    @Override
    public String getName() {
        return "SMLM Real-time Processor";
    }

    @Override
    public String getHelpText() {
        return "Realtime SMLM localization with preview. Tune params, preview a single frame, then Start.";
    }

    @Override
    public String getVersion() {
        return "0.2 ";
    }

    @Override
    public String getCopyright() {
        return "(c) Pranjal Choudhury 2025";
    }

    @Override
    public String getSubMenu() {
        return "Acquisition Tools";
    }

    /**
     * Optional main for local testing (requires ImageJ classes to be on classpath if you try to run).
     */
    public static void main(String[] args) {
        SMLMLocalizer app = new SMLMLocalizer();
        // create minimal wiring so UI works even when Studio is absent (preview will use errors if executed)
        LocalizationProcessor processor = new LocalizationProcessor();
        LocalizationAccumulator accumulator = new LocalizationAccumulator();
        app.acqController_ = new AcquisitionController(null, processor, accumulator);
        app.ui_ = new SMLMLocalizerUI();
        app.ui_.setPreviewCallback(params -> app.acqController_.preview(params));
        app.ui_.setStartCallback(params -> app.acqController_.startAcquisition(params));
        app.ui_.setStopCallback(() -> app.acqController_.requestStop());
        app.ui_.setClearCallback(accumulator::clear);
        SwingUtilities.invokeLater(app.ui_::show);
    }
}
