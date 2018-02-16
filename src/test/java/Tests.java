import edu.univ_tlse3.BFAutofocus;
import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class Tests {

   @Parameterized.Parameters
   public static Collection<Object[]> prepareFiles() {
      String root = System.getProperty("user.dir") + "/src/main/resources/";
      ImagePlus tmpbfimg = IJ.openImage(root + "BF.tif");
//      ImagePlus tmpbfimg = IJ.openImage("/media/tong/screening/17_11_17/BF_1/BF_1_MMStack_mph1.ome.tif");
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
   @Parameterized.Parameter
   public double[]  vars;

   @Parameterized.Parameter(1)
   public ImageProcessor[]  bfProcs;

   @Test
   public void calculateZPositionsTest(){
      double range= 1;
      double step = 0.3;
      double startZ = 0.;
      double[] expected = new double[]{-0.5, -0.2, 0.1, 0.4};
      Assert.assertArrayEquals(expected, BFAutofocus.calculateZPositions(range,step, startZ), 0.01);
   }

   @Test
   public void calculateFocusZPositionTest(){
      Assert.assertEquals(15, BFAutofocus.getZfocus(vars),1);
   }

   @Test
   public void optimizeZFocusTest(){
      double[] zposList = new double[vars.length];
      for (int  i = 0; i< zposList.length; i++){
         zposList[i] = (double) i ;
      }
      Assert.assertEquals(15.5 -1, BFAutofocus.optimizeZFocus(14, vars, zposList),0.3);
   }
}
