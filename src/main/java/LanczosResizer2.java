import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.plugin.filter.GaussianBlur;
import ij.process.ImageProcessor;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.LanczosInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

/**
 * Plane-wise Lanczos resizer for ImageJ/Fiji microscopy images.
 *
 * The original ImagePlus and its pixels are never modified. Each plane is
 * processed through a separate 32-bit floating-point working copy. Spatial
 * X/Y calibration and hyperstack dimensions are preserved in the output.
 *
 * This is 2D plane-wise resampling, not true 3D interpolation through Z.
 *
 * @author Sean Nair
 * @version 1.1.0
 */
public class LanczosResizer implements PlugIn {

    private static final String VERSION = "1.1.0";
    private static final String KEEP_ORIGINAL = "Keep original bit depth";
    private static final String FLOAT_32 = "32-bit float (recommended for quantitative data)";
    private static final String UNSIGNED_16 = "16-bit unsigned (no contrast scaling)";
    private static final String UNSIGNED_8 = "8-bit unsigned (no contrast scaling)";

    @Override
    public void run(final String arg) {
        final ImagePlus input = IJ.getImage();
        if (input == null) {
            IJ.noImage();
            return;
        }

        if (input.getBitDepth() == 24) {
            IJ.error("Unsupported image type",
                    "RGB images are not supported. Split the channels first, " +
                    "or use a greyscale/multichannel hyperstack.");
            return;
        }

        final GenericDialog dialog = new GenericDialog("Lanczos Resizer");
        dialog.addMessage(
                "Creates a new plane-wise Lanczos-resampled image.\n" +
                "The original image is not modified. X/Y calibration is preserved.");
        dialog.addNumericField("Scale factor:", 2.0, 4, 8, "x");
        dialog.addChoice("Output type:", new String[] {
                FLOAT_32, KEEP_ORIGINAL, UNSIGNED_16, UNSIGNED_8
        }, FLOAT_32);
        dialog.addCheckbox("Anti-alias prefilter when downsampling", true);
        dialog.addCheckbox("Write detailed provenance to Image Info", true);
        dialog.showDialog();

        if (dialog.wasCanceled()) return;

        final double scale = dialog.getNextNumber();
        final String outputType = dialog.getNextChoice();
        final boolean antiAlias = dialog.getNextBoolean();
        final boolean writeProvenance = dialog.getNextBoolean();

        if (!Double.isFinite(scale) || scale <= 0.0) {
            IJ.error("Invalid scale", "Scale must be a finite number greater than zero.");
            return;
        }

        final int outputWidth = Math.max(1,
                (int) Math.round(input.getWidth() * scale));
        final int outputHeight = Math.max(1,
                (int) Math.round(input.getHeight() * scale));

        final String inputSha256 = sha256OfStack(input);
        final double prefilterSigma = antiAliasSigma(scale);
        final ImageStack outputStack = new ImageStack(outputWidth, outputHeight);

        for (int plane = 1; plane <= input.getStackSize(); plane++) {
            IJ.showStatus("Lanczos resizing plane " + plane + "/" + input.getStackSize());
            IJ.showProgress(plane - 1, input.getStackSize());

            // convertToFloat() returns a new processor; input pixels are untouched.
            final ImageProcessor working =
                    input.getStack().getProcessor(plane).convertToFloat();

            if (scale < 1.0 && antiAlias && prefilterSigma > 0.0) {
                final GaussianBlur blur = new GaussianBlur();
                blur.blurGaussian(working, prefilterSigma, prefilterSigma, 0.01);
            }

            final Img<FloatType> img = ImageJFunctions.convertFloat(
                    new ImagePlus("working-plane", working));

            final LanczosInterpolatorFactory<FloatType> lanczos =
                    new LanczosInterpolatorFactory<>();

            final RealRandomAccessible<FloatType> interpolated =
                    Views.interpolate(Views.extendBorder(img), lanczos);

            // Pixel-centre mapping: x_in = (x_out + 0.5) / scale - 0.5.
            final double inverseScale = 1.0 / scale;
            final double offset = 0.5 * inverseScale - 0.5;
            final AffineTransform2D transform = new AffineTransform2D();
            transform.set(
                    inverseScale, 0.0, offset,
                    0.0, inverseScale, offset);

            final RealRandomAccessible<FloatType> transformed =
                    RealViews.affine(interpolated, transform);
            final RandomAccessible<FloatType> raster = Views.raster(transformed);
            final RandomAccessibleInterval<FloatType> interval = Views.interval(
                    raster,
                    Intervals.createMinSize(0, 0, outputWidth, outputHeight));

            final ImageProcessor floatOutput =
                    ImageJFunctions.wrap(interval, "resampled-plane").getProcessor();
            final ImageProcessor converted = convertOutput(
                    floatOutput, outputType, input.getBitDepth());

            outputStack.addSlice(input.getStack().getSliceLabel(plane), converted);
        }

        ImagePlus output = new ImagePlus(
                input.getShortTitle() + "_Lanczos_" + scale + "x", outputStack);

        final int channels = input.getNChannels();
        final int slices = input.getNSlices();
        final int frames = input.getNFrames();
        if (channels * slices * frames == input.getStackSize()) {
            output.setDimensions(channels, slices, frames);
            output.setOpenAsHyperStack(input.isHyperStack());
        }

        final Calibration calibration =
                (Calibration) input.getCalibration().clone();
        calibration.pixelWidth /= scale;
        calibration.pixelHeight /= scale;
        // pixelDepth and time calibration are intentionally unchanged.
        output.setCalibration(calibration);

        // Display range affects visualisation only; it does not alter pixel values.
        output.setDisplayRange(input.getDisplayRangeMin(), input.getDisplayRangeMax());

        if (writeProvenance) {
            appendProvenance(input, output, scale, outputType, antiAlias,
                    prefilterSigma, inputSha256);
        }

        output.show();
        IJ.showProgress(1.0);
        IJ.showStatus("Lanczos resizing complete; original image unchanged.");
    }

    private static ImageProcessor convertOutput(final ImageProcessor floatOutput,
            final String outputType, final int originalBitDepth) {
        if (FLOAT_32.equals(outputType)) return floatOutput.convertToFloat();
        if (UNSIGNED_16.equals(outputType)) return floatOutput.convertToShort(false);
        if (UNSIGNED_8.equals(outputType)) return floatOutput.convertToByte(false);

        if (KEEP_ORIGINAL.equals(outputType)) {
            if (originalBitDepth == 8) return floatOutput.convertToByte(false);
            if (originalBitDepth == 16) return floatOutput.convertToShort(false);
        }
        return floatOutput.convertToFloat();
    }

    private static double antiAliasSigma(final double scale) {
        if (scale >= 1.0) return 0.0;
        return 0.5 * Math.sqrt((1.0 / (scale * scale)) - 1.0);
    }

    private static void appendProvenance(final ImagePlus input,
            final ImagePlus output, final double scale, final String outputType,
            final boolean antiAlias, final double sigma, final String inputSha256) {
        final Calibration inCal = input.getCalibration();
        final Calibration outCal = output.getCalibration();
        final Object infoProperty = input.getProperty("Info");
        final String originalInfo = infoProperty instanceof String
                ? (String) infoProperty : "";

        final StringBuilder log = new StringBuilder();
        log.append("\n--- Lanczos Resizer provenance ---\n");
        log.append("Plugin version: ").append(VERSION).append('\n');
        log.append("Timestamp (UTC): ").append(Instant.now()).append('\n');
        log.append("ImageJ version: ").append(IJ.getVersion()).append('\n');
        log.append("Java version: ").append(System.getProperty("java.version")).append('\n');
        log.append("Operation: 2D plane-wise Lanczos resampling\n");
        log.append("True 3D interpolation: no\n");
        log.append("Original modified: no\n");
        log.append("Original title: ").append(input.getTitle()).append('\n');
        log.append("Input SHA-256: ").append(inputSha256).append('\n');
        log.append("Input dimensions: ").append(input.getWidth()).append('x')
                .append(input.getHeight()).append("; planes=")
                .append(input.getStackSize()).append('\n');
        log.append("Output dimensions: ").append(output.getWidth()).append('x')
                .append(output.getHeight()).append("; planes=")
                .append(output.getStackSize()).append('\n');
        log.append("C/Z/T: ").append(input.getNChannels()).append('/')
                .append(input.getNSlices()).append('/')
                .append(input.getNFrames()).append('\n');
        log.append("Scale factor: ").append(scale).append('\n');
        log.append("Coordinate mapping: (output + 0.5) / scale - 0.5\n");
        log.append("Boundary extension: border\n");
        log.append("Output type: ").append(outputType).append('\n');
        log.append("Automatic contrast scaling during conversion: no\n");
        log.append("Anti-alias prefilter applied: ")
                .append(scale < 1.0 && antiAlias).append('\n');
        if (scale < 1.0 && antiAlias) {
            log.append("Gaussian prefilter sigma (pixels): ").append(sigma).append('\n');
        }
        log.append("Input pixel size X/Y/Z: ")
                .append(inCal.pixelWidth).append('/')
                .append(inCal.pixelHeight).append('/')
                .append(inCal.pixelDepth).append(' ')
                .append(inCal.getUnit()).append('\n');
        log.append("Output pixel size X/Y/Z: ")
                .append(outCal.pixelWidth).append('/')
                .append(outCal.pixelHeight).append('/')
                .append(outCal.pixelDepth).append(' ')
                .append(outCal.getUnit()).append('\n');
        log.append("--- End provenance ---\n");

        output.setProperty("Info", originalInfo + log);
    }

    private static String sha256OfStack(final ImagePlus image) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int plane = 1; plane <= image.getStackSize(); plane++) {
                final Object pixels = image.getStack().getProcessor(plane).getPixels();
                if (pixels instanceof byte[]) {
                    digest.update((byte[]) pixels);
                } else if (pixels instanceof short[]) {
                    for (short value : (short[]) pixels) {
                        digest.update(ByteBuffer.allocate(2).putShort(value).array());
                    }
                } else if (pixels instanceof float[]) {
                    for (float value : (float[]) pixels) {
                        digest.update(ByteBuffer.allocate(4).putFloat(value).array());
                    }
                } else if (pixels instanceof int[]) {
                    for (int value : (int[]) pixels) {
                        digest.update(ByteBuffer.allocate(4).putInt(value).array());
                    }
                }
            }
            final StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            return "unavailable: " + error.getMessage();
        }
    }
}
