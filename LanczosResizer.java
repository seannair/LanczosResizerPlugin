import ij.*;
import ij.process.*;
import ij.gui.*;
import ij.plugin.PlugIn;
import ij.measure.Calibration;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusAdapter;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.LanczosInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.imglib2.util.Intervals;

public class LanczosResizer implements PlugIn {

    public void run(String arg) {
        // Prompt
        GenericDialog gd = new GenericDialog("Lanczos Resizer");
        gd.addNumericField("Scale factor:", 2.0, 2);
        gd.addChoice("Bit depth:", new String[]{"8", "16"}, "16");
        gd.showDialog();
        if (gd.wasCanceled()) return;

        double scale = gd.getNextNumber();
        int bitDepth = Integer.parseInt(gd.getNextChoice());

        ImagePlus imp = IJ.getImage();
        int width = (int) (imp.getWidth() * scale);
        int height = (int) (imp.getHeight() * scale);
        int slices = imp.getStackSize();

        ImageStack resizedStack = new ImageStack(width, height);
        Calibration cal = imp.getCalibration();

        for (int z = 1; z <= slices; z++) {
            IJ.showStatus("Resizing slice " + z + "/" + slices);
            IJ.showProgress(z, slices);
            ImageProcessor ip = imp.getStack().getProcessor(z).convertToFloat();

            // Convert to ImgLib2
            Img<FloatType> img = ImagePlusAdapter.wrapFloat(new ImagePlus("", ip));

            // Interpolator
            InterpolatorFactory<FloatType, ?> factory = new LanczosInterpolatorFactory<>();
            RealRandomAccessible<FloatType> interpolated = Views.interpolate(Views.extendZero(img), factory);

            // Transform
            AffineTransform2D transform = new AffineTransform2D();
            transform.set(scale, 0, 0, 0, scale, 0);
            RealRandomAccessible<FloatType> transformed = RealViews.affine(interpolated, transform);

            // Interval and output
            RandomAccessibleInterval<FloatType> output = Views.interval(transformed,
                    Intervals.createMinSize(new long[]{0, 0}, new long[]{width, height}));

            // Convert back to ImageProcessor
            ImagePlus temp = ImageJFunctions.wrap(output, "Slice " + z);
            ImageProcessor resized = temp.getProcessor();

            // Convert bit depth
            if (bitDepth == 8) resized = resized.convertToByte(true);
            else if (bitDepth == 16) resized = resized.convertToShort(true);

            resizedStack.addSlice(imp.getStack().getSliceLabel(z), resized);
        }

        ImagePlus result = new ImagePlus("Lanczos Resized", resizedStack);
        result.setCalibration(cal);
        result.show();
    }
}
