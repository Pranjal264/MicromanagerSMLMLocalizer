package org.micromanager.smlm;

import org.micromanager.MenuPlugin;
import org.micromanager.Studio;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.SciJavaPlugin;

import javax.swing.*;

/**
 * Entrypoint for Micro-Manager.
 * Wires the UI, the acquisition controller, the processor and the accumulator.
 */
@Plugin(type = MenuPlugin.class)
public class SMLMLocalizer implements MenuPlugin, SciJavaPlugin {

    private Studio studio_;
    private SMLMLocalizerUI ui_;
    private AcquisitionController acqController_;

    public SMLMLocalizer() {
        // Studio context arrives in setContext
    }

    @Override
    public void setContext(Studio studio) {
        this.studio_ = studio;
        wire(studio);
    }

    /** Create processor, accumulator, controller and UI and connect them. */
    private void wire(Studio studio) {
        LocalizationProcessor processor = new LocalizationProcessor();
        LocalizationAccumulator accumulator = new LocalizationAccumulator();

        acqController_ = new AcquisitionController(studio, processor, accumulator);
        ui_ = new SMLMLocalizerUI();
        acqController_.setProgressListener(ui_);

        final AcquisitionController ctrl = acqController_;
        ui_.setPreviewCallback(ctrl::preview);
        ui_.setStartCallback(ctrl::startAcquisition);
        ui_.setStopCallback(ctrl::requestStop);
        ui_.setClearCallback(accumulator::clear);
    }

    @Override
    public void onPluginSelected() {
        SwingUtilities.invokeLater(() -> {
            if (ui_ == null) wire(null);   // setContext not called (local run)
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
        return "0.4";
    }

    @Override
    public String getCopyright() {
        return "(c) Pranjal Choudhury 2026";
    }

    @Override
    public String getSubMenu() {
        return "Acquisition Tools";
    }

    /** Optional main for local testing (preview/acquire from hardware will report "Studio not available"). */
    public static void main(String[] args) {
        SMLMLocalizer app = new SMLMLocalizer();
        app.wire(null);
        SwingUtilities.invokeLater(app.ui_::show);
    }
}
