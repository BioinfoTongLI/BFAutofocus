import edu.univ_tlse3.BFAutofocus;
import ij.IJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class BFAutofocusTest {

	@Parameterized.Parameter
	public double[] vars;
	@Parameterized.Parameter(1)
	public ImageProcessor[] bfProcs;
	@Parameterized.Parameter(2)
	public ImagePlus srcImp;
	@Parameterized.Parameter(3)
	public ImagePlus trgImp;

	@Parameterized.Parameters
	public static Collection<Object[]> prepareFiles() {
		String root = System.getProperty("user.dir") + "/src/main/resources/";
		ImagePlus tmpbfimg = IJ.openImage(root + "BF.tif");
		int zsliceNb = tmpbfimg.getDimensions()[3];
		double[] varArray = new double[zsliceNb];
		ImageProcessor[] bfProcessors = new ImageProcessor[zsliceNb];
		ImageProcessor currentProc;
		for (int i = 1; i < zsliceNb + 1; i++) {
			currentProc = tmpbfimg.getStack().getProcessor(i);
			bfProcessors[i - 1] = currentProc;
			varArray[i - 1] = currentProc.getStatistics().stdDev;
		}
		return Arrays.asList(new Object[][]{{varArray, bfProcessors,
				IJ.openImage(root + "MMStack_wt.ome-3.tif"),
				IJ.openImage(root + "MMStack_wt.ome-2.tif")}});
	}

	@Test
	public void calculateZPositionsTest() {
		double range = 1;
		double step = 0.3;
		double startZ = 0.;
		double[] expected = new double[]{-0.5, -0.2, 0.1, 0.4};
		Assert.assertArrayEquals(expected, BFAutofocus.calculateZPositions(range, step, startZ), 0.01);
	}

	@Test
	public void testAlignment() {
		// method 2, 3, 4, 5 gives the same result
		FloatProcessor scoreImg = BFAutofocus.doMatch(trgImp, srcImp, 3);
		int[] dxdy = BFAutofocus.findMax(scoreImg, 0);
      /*
         987 and 729 are the X and Y of the selected position in the original image
       */

		int disX = 987 - dxdy[0];
		int disY = 729 - dxdy[1];
		Assert.assertEquals(-108, disX, 0.0);
		Assert.assertEquals(-9, disY, 0.0);
	}
}
