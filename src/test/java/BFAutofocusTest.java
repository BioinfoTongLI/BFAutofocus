import edu.univ_tlse3.BFAutofocus;
import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

@RunWith(Parameterized.class)
class BFAutofocusTest {

    @Parameterized.Parameters
    public static Collection<Object[]> prepareFiles() {
        nu.pattern.OpenCV.loadShared();
        String root = System.getProperty("user.dir") + "/src/main/resources/";
        ImagePlus tmpbfimg = IJ.openImage(root + "BF.tif");
        int zsliceNb = tmpbfimg.getDimensions()[3];
        double[] varArray = new double[zsliceNb];
        ImageProcessor[] bfProcessors = new ImageProcessor[zsliceNb];
        ImageProcessor currentProc;
        for (int i = 1; i< zsliceNb+1 ; i++){
            currentProc = tmpbfimg.getStack().getProcessor(i);
            bfProcessors[i-1] = currentProc;
            varArray[i-1] = currentProc.getStatistics().stdDev;
        }
        return Arrays.asList(new Object[][] {{varArray, bfProcessors}});
    }

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
        double range= 1;
        double step = 0.3;
        double startZ = 0.;
        double[] expected = new double[]{-0.5, -0.2, 0.1, 0.4};
        Assert.assertArrayEquals(expected, BFAutofocus.calculateZPositions(range,step, startZ), 0.01);
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
