import edu.univ_tlse3.BFAutofocus;
import ij.IJ;
import ij.ImagePlus;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class BFAutofocusTest {
	
	@Test
	void getLabelOfPositionsTest() {
	}
	
	@Test
	void getFeatureDetectorIndexTest() {
	}
	
	@Test
	void getDescriptorExtractorIndexTest() {
	}
	
	@Test
	void writeMultipleOutputTest() {
	}
	
	@Test
	void writeOutputTest() {
	}
	
	@Test
	void resetParametersTest() {
	}
	
	@Test
	void resetInitialMicroscopeConditionTest() {
	}
	
	@Test
	void finalizeAcquisitionTest() {
	}
	
	@Test
	void getXYZPositionTest() {
	}
	
	@Test
	void refreshOldXYZpositionTest() {
	}
	
	@Test
	void setToLastCorrectedPositionTest() {
	}
	
	@Test
	void getZPositionTest() {
	}
	
	@Test
	void getZfocusTest() {
	}
	
	@Test
	void calculateZPositionsTest() {
		double range = 1;
		double step = 0.3;
		double startZ = 0.;
		double[] expected = new double[]{-0.5, -0.2, 0.1, 0.4};
		Assert.assertArrayEquals(expected, BFAutofocus.calculateZPositions(range, step, startZ), 0.01);
	}
	
	@Test
	void optimizeZFocusTest() {
	}
	
	@Test
	void calculateZFocusTest() {
	}
	
	@Test
	void setZPositionTest() {
	}
	
	@Test
	void calculateXYDriftsTest() {
		String root = System.getProperty("user.dir") + "/src/main/resources/";
		ImagePlus srcimg = IJ.openImage(root + "T0.tif");
		ImagePlus tagimg = IJ.openImage(root + "T10.tif");
		ExecutorService es = Executors.newSingleThreadExecutor();
		Future job = es.submit(new BFAutofocus.ThreadAttribution(srcimg, tagimg));
		double[] xyDrifts = new double[2];
		try {
			xyDrifts = (double[]) job.get();
		} catch (Exception e1) {
			e1.printStackTrace();
		}
		
		es.shutdown();
		try {
			es.awaitTermination(1, TimeUnit.MINUTES);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		Assert.assertEquals(2.055, xyDrifts[0], 0.001);
		
		Assert.assertEquals(-2.602, xyDrifts[1], 0.001);
	}
	
	@Test
	void calculateMultipleXYDriftsTest() {
	}
	
	@Test
	void setXYPositionTest() {
	}
	
	@Test
	void convertToMatTest() {
	}
	
	@Test
	void convertTo8BitsMatTest() {
	}
}
