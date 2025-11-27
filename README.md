# MicromanagerSMLMLocalizer
It is Micro-Manager 2.0 plugin for near-real-time single-molecule localization processing.  
It provides:

- Live preview or acquisition, detection of PSFs and Super resolved image (Accumulated localization histogram).
- Save per-frame TIFFs **or** a plain multipage TIFF (ImageJ stack )
- CSV export of localizations (Frame, Amplitude, X, Y)
- Simulation mode: process TIFFs from a folder as if they were camera frames

---

## Table of contents

- [Requirements](#requirements)  
- [Build & Install (NetBeans + Ant)](#build--install-netbeans--ant)  
- [Usage](#usage)  
- [Configuration notes & troubleshooting](#configuration-notes--troubleshooting)  
- [Contributing](#contributing)  
- [License](#license)

---

## Requirements

- Micro-Manager 2.0 (plugin discovery via SciJava)  
- Java 1.8 (the JDK used by your Micro-Manager distribution)  
- NetBeans (you can use any IDE but instructions below assume NetBeans + Ant)  
- Dependencies (add these jars to your micromanager install directory \ plugins \Micro-Manager):
  - [JTransforms] (for FFT) - `JTransforms-3.*.jar` 
  - [JLargeArrays] (for large arrays) -  `JLargeArrays-1.*.jar`

> Note: the plugin uses SciJava annotations. Make sure annotation processing is enabled in your NetBeans/Ant build so plugin metadata is generated.

---

## Build & Install (NetBeans + Ant)

1. **Project setup**
   - CLone the repositiory. There is a Netbeans +Ant project within.
   - If there are issues, create a Java project in NetBeans using Ant.
   - Follow the instructions provided at [Micromanager website](https://micro-manager.org/Using_Netbeans)
   - Ensure annotation processing is enabled (NetBeans -> Project Properties -> Annotation Processors -> Enable).
   - Clean and Build

2. **Install into Micro-Manager**
   - Copy `dist/SMLMLocalizer.jar` (and any dependency jars not provided by μManager) into the `mmplugins` directory inside the micromanager installation.
   - Restart Micro-Manager. The plugin should appear under the menu specified by the SciJava `MenuPlugin` metadata (e.g., **Acquisition Tools → SMLM Real-time Processor**).

---

## Usage

1. Start Micro-Manager and open your camera/live view.
2. (Optional) Set desired camera ROI from the Micro-Manager main GUI - the plugin will see the ROI and process ROI-sized frames.
3. Open the plugin: `Plugins → Acquisition Tools → SMLM Real-time Processor`.
4. Tune parameters in the UI:
   - Frames, exposure, mag, update interval
   - Background / smoothing sigma, min peak distance, threshold, ROI size
5. Choose saving mode:
   - **Save as multi-page TIFF** (ImageJ stack; saved at end of acquisition; no OME metadata)
   - **Save per-frame TIFF files**
6. Click **Preview** to process a single frame (from simulation folder or live camera).
7. Click **Start** to begin acquisition. Progress bar + ETA appear in the plugin GUI.
8. Once complete, `localizations.csv` (Frame,Amplitude,X,Y) will be in the output folder.

---

## Configuration notes & troubleshooting

- **ROI handling**: If you select an ROI in the Micro-Manager main GUI, the plugin will receive frames sized to that ROI (`core.getImageWidth()` / `core.getImageHeight()`), so you can tune parameters on your chosen ROI.
- **Multipage TIFF memory usage**: The multipage TIFF mode currently collects frames in an `ImageStack` and writes the TIFF at the end. For very large numbers of frames or very large frame sizes this may use a lot of memory. If you need streaming (append-as-you-go) saving, open an issue and we can add a streaming writer.
- **Bio-Formats / OME**: This plugin intentionally saves a plain multipage TIFF (no OME metadata). If you require OME metadata and Bio-Formats writing, we can add a BF writer mode - but you may need to address metadata configuration (sizeX/sizeY/sizeT etc.).
- **If saving fails**: check that the output folder is writable and that you have enough free disk space. The plugin logs messages to Micro-Manager's logs panel.
- **CSV format**: `localizations.csv` contains `Frame,Amplitude,X,Y` columns (no image filenames by default).

---

## Contributing

- Please open issues for bugs/feature requests.
- Pull requests welcome - please keep changes small and document any external dependency added.
- If you contribute code, include tests (where feasible) and keep API changes backward-compatible.

---

## License

This repository is released under the **BSD 3-Clause License**. See [LICENSE](./LICENSE) for details.

---

## Author / Citation

Pranjal Choudhury - SMLM plugin (2025)  
If you use this plugin in published work, please cite it (include plugin name and version) or open an issue and request a suggested citation format.

