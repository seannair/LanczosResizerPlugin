import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;

import java.util.Arrays;
import java.util.Random;

import net.imglib2.RealRandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.LanczosInterpolatorFactory;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Headless tests for LanczosResizer. The surefire configuration runs the forked
 * JVM with -Djava.awt.headless=true so no GUI is created on machines that have a display.
 */
public class LanczosResizerTest {

    @BeforeClass
    public static void initImageJ() {
        new IJ(); // initialise ImageJ core (skips the GUI when headless)
    }

    @Test
    public void upscaleConstantKeepsValuesAndHalvesCalibration() {
        final int w = 64, h = 48;
        final short[] pixels = new short[w * h];
        Arrays.fill(pixels, (short) 1000);
        final ImagePlus input = new ImagePlus("const", new ShortProcessor(w, h, pixels, null));
        input.getCalibration().pixelWidth = 2.5;
        input.getCalibration().pixelHeight = 2.5;

        final ImagePlus output =
                new LanczosResizer().run(input, 2.0, LanczosResizer.FLOAT_32, true, true);

        assertNotNull(output);
        assertEquals(128, output.getWidth());
        assertEquals(96, output.getHeight());
        assertEquals(1.25, output.getCalibration().pixelWidth, 1e-9);
        assertEquals(1.25, output.getCalibration().pixelHeight, 1e-9);

        // Lanczos with border extension keeps a constant field approximately
        // constant: imglib2's windowed-sinc kernel is not renormalised, so its
        // weights sum to ~1 +/- 0.6% depending on sample position (a corner of
        // the upsampled image measured 993.8 for a 1000 field). A tolerance of
        // 20 covers that while still catching gross resampling errors.
        final float[] out = (float[]) output.getProcessor().getPixels();
        for (int i = 0; i < out.length; i++) {
            assertEquals(1000.0, out[i], 20.0);
        }

        // Provenance block must be present when requested.
        final Object info = output.getProperty("Info");
        assertNotNull(info);
    }

    @Test
    public void upscaleRampMatchesPixelCentreMapping() {
        final int w = 64;
        final float[] pixels = new float[w * w];
        for (int y = 0; y < w; y++)
            for (int x = 0; x < w; x++)
                pixels[y * w + x] = x; // ramp along X, constant along Y

        final ImagePlus input = new ImagePlus("ramp", new FloatProcessor(w, w, pixels));

        final ImagePlus output =
                new LanczosResizer().run(input, 2.0, LanczosResizer.FLOAT_32, true, false);

        assertNotNull(output);
        assertEquals(128, output.getWidth());
        assertEquals(128, output.getHeight());

        // Pixel-centre mapping: output pixel j samples the input at
        // (j + 0.5) / scale - 0.5 = j/2 - 0.25 for scale 2. Pin that exact
        // mapping by comparing against a direct imglib2 Lanczos3 evaluation at
        // those source positions; an inverted transform, missing offset or
        // wrong scale would all fail here. Both paths end in float rounding of
        // the same double accumulation, so agreement is tight.
        final Img<FloatType> direct = ImageJFunctions.convertFloat(
                new ImagePlus("ramp-direct", new FloatProcessor(w, w, pixels.clone())));
        final RealRandomAccessible<FloatType> interp = Views.interpolate(
                Views.extendBorder(direct), new LanczosInterpolatorFactory<>());
        final float[] out = (float[]) output.getProcessor().getPixels();
        for (int col : new int[] {10, 30, 64, 98}) {
            final double expected =
                    interp.getAt(col / 2.0 - 0.25, 10 / 2.0 - 0.25).getRealDouble();
            assertEquals(expected, out[10 * 128 + col], 1e-4);
        }

        // Sanity bound against the ideal ramp value: imglib2's Lanczos3 uses a
        // truncated 6-tap windowed-sinc (LUT-quantised) that is NOT renormalised,
        // so its weights sum to ~0.994 and a linear ramp drifts from the ideal by
        // up to ~0.4 across this image width. Still far tighter than half a pixel.
        for (int col : new int[] {30, 64, 98}) {
            assertEquals(col / 2.0 - 0.25, out[10 * 128 + col], 0.5);
        }
    }

    @Test
    public void downsampleFloatInputLeavesOriginalUntouched() {
        final int w = 64, h = 48;
        final float[] pixels = new float[w * h];
        final Random rng = new Random(1234);
        for (int i = 0; i < pixels.length; i++) pixels[i] = rng.nextFloat() * 65535f;
        final ImagePlus input = new ImagePlus("random", new FloatProcessor(w, h, pixels));
        final float[] before = pixels.clone();

        // scale < 1 with antiAlias = true exercises the Gaussian prefilter path.
        final ImagePlus output =
                new LanczosResizer().run(input, 0.5, LanczosResizer.FLOAT_32, true, false);

        assertArrayEquals(before, pixels, 0f);

        assertNotNull(output);
        assertEquals(32, output.getWidth());
        assertEquals(24, output.getHeight());
    }

    @Test
    public void hyperstackDimensionsArePreserved() {
        // C=1 Z=2 T=2 => 4 planes of 16x16, each with a distinct constant value.
        final int w = 16;
        final ImageStack stack = new ImageStack(w, w);
        for (int p = 0; p < 4; p++) {
            final short[] px = new short[w * w];
            Arrays.fill(px, (short) (p + 1));
            stack.addSlice("plane" + (p + 1), new ShortProcessor(w, w, px, null));
        }
        final ImagePlus input = new ImagePlus("stack", stack);
        input.setDimensions(1, 2, 2);

        final ImagePlus output =
                new LanczosResizer().run(input, 0.5, LanczosResizer.UNSIGNED_16, true, false);

        assertNotNull(output);
        assertEquals(8, output.getWidth());
        assertEquals(8, output.getHeight());
        assertEquals(2, output.getNSlices());
        assertEquals(2, output.getNFrames());
    }
}
