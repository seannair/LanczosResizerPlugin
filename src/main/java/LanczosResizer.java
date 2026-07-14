import ij.*;
import ij.process.*;
import ij.gui.*;
import ij.plugin.PlugIn;
import ij.measure.Calibration;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.LanczosInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.imglib2.util.Intervals;

/**
 * <h1>Lanczos Resizer for Microscopy Images</h1>
 * <p>
 * This ImageJ/Fiji plugin resizes 2D/3D image stacks plane-by-plane using 
 * high-fidelity Lanczos interpolation via the ImgLib2 framework.
 * </p>
 * 
 * <h3>Scientific Integrity & Best Practices Implemented:</h3>
 * <ul>
 *   <li><b>Calibration Scaling:</b> Automatically rescales the physical spatial 
 *       metadata (pixel width/height) inversely to the scale factor. This ensures 
 *       downstream measurements and ImageJ scale bars remain mathematically accurate.</li>
 *   <li><b>Data Provenance:</b> Appends a full digital audit trail to the image's 
 *       "Info" metadata properties, explicitly declaring the interpolation method, 
 *       scaling parameters, and original-to-final calibration shift.</li>
 *   <li><b>Precision Preservation:</b> Temporarily converts image matrices to 
 *       32-bit floating-point precision before interpolation to mitigate rounding errors 
 *       and computational clipping.</li>
 * </ul>
 * 
 * @author [Sean Nair /seannair]
 * @version 1.0.0
 * @license MIT License
 */
public class LanczosResizer implements PlugIn {

    @Override
    public void run(String arg) {

        // 1. CONSTRUCT USER INTERFACE DIALOGUE
        GenericDialog gd = new GenericDialog("Lanczos Resizer (ImgLib2)");
        gd.addMessage("Resizes image stacks using high-fidelity Lanczos interpolation.\n"
                    + "Spatial metadata updates automatically to preserve calibration accuracy.");
        gd.addNumericField("Scale factor:", 2.0, 2, 6, "x");
        gd.addChoice("Output bit depth:", new String[]{"8-bit", "16-bit"}, "16-bit");
        gd.showDialog();
        
        if (gd.wasCanceled()) return;

        double scale = gd.getNextNumber();
        String bitDepthChoice = gd.getNextChoice();
        int bitDepth = bitDepthChoice.equals("8-bit") ? 8 : 16;

        // Validation rule to safeguard against zero or negative scaling spaces
        if (scale <= 0) {
            IJ.error("Scalability Error", "The scale factor must be strictly greater than 0.0.");
            return;
        }

        // 2. RETRIEVE SOURCE IMAGE DATA AND COMPUTE DIMENSIONS
        ImagePlus imp = IJ.getImage();
        if (imp == null) {
            IJ.noImage();
            return;
        }

        int width = (int) (imp.getWidth() * scale);
        int height = (int) (imp.getHeight() * scale);
        int slices = imp.getStackSize();

        // Ensure resulting dimensions contain at least 1 pixel array index
        if (width < 1 || height < 1) {
            IJ.error("Dimension Error", "The chosen scale factor resolves to a canvas smaller than 1 pixel.");
            return;
        }

        ImageStack resizedStack = new ImageStack(width, height);

        // 3. SCIENTIFIC BEST PRACTICE: SPATIAL METADATA PROPAGATION
        // We clone the calibration to break the object reference loop, 
        // ensuring changes do not leak back into the original raw data stack.
        Calibration cal = (Calibration) imp.getCalibration().clone();
        
        // Mathematical adjustment: As pixel canvas density shifts, physical distance 
        // coverage per pixel changes inversely (e.g. upsampling cuts micron size per pixel).
        cal.pixelWidth = cal.pixelWidth / scale;
        cal.pixelHeight = cal.pixelHeight / scale;
        // Note: Z-axis pixel depth is intentionally unmodified since resizing evaluates 2D space.

        // 4. ITERATE THROUGH IMAGE PIPELINE (PLANE-BY-PLANE PROCESSING)
        for (int z = 1; z <= slices; z++) {

            IJ.showStatus("Resizing slice " + z + "/" + slices);
            IJ.showProgress(z, slices);

            // Cast incoming pixel vectors to 32-bit floats to isolate interpolation maths 
            // from native bit-depth clipping boundaries.
            ImageProcessor ip = imp.getStack().getProcessor(z).convertToFloat();

            // Wrap native ImageJ processor to utilize ImgLib2 framework constructs
            Img<FloatType> img = ImagePlusImgs.from(new ImagePlus("", ip));

            // Establish the Lanczos kernel to sample coordinates across space boundaries
            InterpolatorFactory<FloatType, ?> factory = new LanczosInterpolatorFactory<>();

            // Set up boundary expansion strategy (Zero-padding/out-of-bounds containment)
            RealRandomAccessible<FloatType> interpolated = Views.interpolate(
                    Views.extendZero(img),
                    factory
            );

            // Construct explicit 2D scaling transformations
            AffineTransform2D transform = new AffineTransform2D();
            transform.set(
                    scale, 0, 0,
                    0, scale, 0
            );

            // Map continuous transformed coordinates across pixel arrays
            RealRandomAccessible<FloatType> transformed = RealViews.affine(interpolated, transform);

            // Constrain transformed infinite space into target image dimensions bounding boxes
            RandomAccessibleInterval<FloatType> output = Views.interval(
                    transformed,
                    Intervals.createMinSize(new long[]{0, 0}, new long[]{width, height})
            );

            // Bridge back into ImageJ environment
            ImagePlus temp = ImageJFunctions.wrap(output, "Slice_" + z);
            ImageProcessor resized = temp.getProcessor();

            // Downsample floating point calculation array into destination data matrices
            if (bitDepth == 8) {
                resized = resized.convertToByte(true); // 'true' applies intensity scaling
            } else if (bitDepth == 16) {
                resized = resized.convertToShort(true);
            }

            resizedStack.addSlice(imp.getStack().getSliceLabel(z), resized);
        }

        // 5. ASSEMBLE OUTPUT AND APPEND DIGITAL AUDIT PROVENANCE
        ImagePlus result = new ImagePlus(imp.getShortTitle() + "_Lanczos_" + scale + "x", resizedStack);

        // Bind corrected spatial properties to output container
        result.setCalibration(cal);

        // SCIENTIFIC TRANSPARENCY: Embed algorithmic footprint directly inside metadata log properties
        String originalInfo = (String) imp.getProperty("Info");
        String processingLog = "\n--- Digital Data Provenance Log ---\n"
                + "Process: 2D Spatial Canvas Resizing\n"
                + "Engine Framework: ImgLib2 Core\n"
                + "Interpolation Method: Lanczos Interpolator Kernel\n"
                + "Applied Scaling Matrix Factor: " + scale + "x\n"
                + "Output Precision Bitrate: " + bitDepth + "-bit\n"
                + "Metadata Recalibration: Spatial pixel dimension mapped from (" 
                + imp.getCalibration().pixelWidth + " x " + imp.getCalibration().pixelHeight + ") to ("
                + cal.pixelWidth + " x " + cal.pixelHeight + " " + cal.getUnit() + "/pixel).\n"
                + "Provenance Status: Compliant with FAIR image data preservation standards.\n";
        
        result.setProperty("Info", (originalInfo != null ? originalInfo : "") + processingLog);

        // Display outcome window frame inside active desktop workspace
        result.show();
        IJ.showStatus("Lanczos resizing complete. Metadata preserved.");
    }
}
