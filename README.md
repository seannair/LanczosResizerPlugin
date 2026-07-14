# Lanczos Microscopy Resizer (ImgLib2 + ImageJ/Fiji)

A plugin designed to scale 2D and 3D image stacks plane-by-plane using high-fidelity Lanczos interpolation via the ImgLib2 framework. 

Unlike standard resizing tools that break image metadata, this plugin automatically recalculates spatial calibration and embeds a digital audit trail directly into the file's properties.

## 🚀 How to Install and Use in Fiji / ImageJ

### 1. Installation
1. Download the `LanczosResizer.java` file from this repository.
2. Open **Fiji / ImageJ**.
3. Drag and drop the `LanczosResizer.java` file directly onto the Fiji search bar, or save it into your Fiji directory under the `/plugins/` folder and restart Fiji.

### 2. Running the Tool
1. Open your microscopy image stack in Fiji.
2. Run the plugin by navigating to `Plugins > Lanczos Resizer`.
3. A dialogue window will appear:
   * **Scale Factor:** Input your desired scale (e.g., `2.0` to double the size, `0.5` to halve it).
   * **Output Bit Depth:** Choose **16-bit** (recommended) or **8-bit** (ideal for publication/presentations).
4. Click **OK**. 

### 3. Verifying Results & Scale Bars
* Go to `Analyze > Tools > Scale Bar...`.
* The scale bar text size will scale naturally with your new resolution.
* Press `i` on your keyboard to view the automated log confirming the math.
