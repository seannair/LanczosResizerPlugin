# Lanczos Microscopy Resizer (ImgLib2 + ImageJ/Fiji)

A plugin designed to scale 2D and 3D image stacks plane-by-plane using high-fidelity Lanczos interpolation via the ImgLib2 framework. It recalculates spatial X/Y calibration automatically and can embed a digital audit trail directly into the file's properties.

## When to use this (vs the built-in tools)

For everyday resizing, Fiji's built-in **Image > Scale…** is usually enough: it offers **Lanczos3** interpolation, updates pixel-size calibration, and never touches the original image. Use *this* plugin when you need what the built-in tool does not provide:

- **Anti-alias Gaussian prefilter on downsampling** (optional, on by default) — reduces aliasing artifacts when reducing resolution; `Image > Scale` resamples directly without a low-pass stage.
- **Provenance audit trail** — writes plugin version, timestamp, input SHA-256 hash, exact coordinate mapping, and before/after calibration into the image's Info panel (`i`).

Note: this is 2D plane-wise resampling (each XY plane is processed independently). Z and time calibrations are preserved but not interpolated through.

## 🚀 How to Install in Fiji / ImageJ

### Option A — build the JAR (recommended)
1. Build with Maven (`mvn clean package`); requires JDK 11+ and Maven.
2. Copy `target/lanczos-resizer-1.2.0.jar` into your Fiji installation's `jars/` folder, or use **Plugins > Install…** in Fiji and point it at the jar.
3. Restart Fiji. The plugin appears under **Plugins > Microscopy > Lanczos Resizer**.

### Option B — drop-in source file
1. Save `LanczosResizer.java` into your Fiji installation's `plugins/` folder.
2. Restart Fiji. It is auto-compiled and registered as **Plugins > LanczosResizer** (auto-discovered by class name).

> Dragging the `.java` file onto the Fiji search bar only compiles and runs it once — it does *not* install a menu entry.

### 1. Running the Tool
1. Open your microscopy image stack in Fiji.
2. Run **Plugins > Microscopy > Lanczos Resizer** (or **Plugins > LanczosResizer**).
3. A dialogue window will appear:
   * **Scale Factor:** input your desired scale (e.g., `2.0` to double the size, `0.5` to halve it).
   * **Output Bit Depth:** 32-bit float (recommended for quantitative data), keep original, 16-bit, or 8-bit. Conversions are straight value mappings — no automatic contrast rescaling is applied.
   * **Anti-alias prefilter when downsampling:** on by default; only applied when scale < 1.
   * **Write detailed provenance to Image Info:** on by default.
4. Click **OK**. The original image and its pixels are never modified.

### 2. Verifying Results & Scale Bars
* Go to `Analyze > Tools > Scale Bar...`.
* The scale bar text size will scale naturally with your new resolution.
* Press `i` on your keyboard to view the provenance log confirming the calibration math and input hash.

## Limitations
- RGB images are not supported — split the channels first, or use a greyscale/multichannel hyperstack.
- 32-bit *integer* inputs are misinterpreted by ImageJ's type-conversion path; use 8/16-bit or 32-bit float data.
