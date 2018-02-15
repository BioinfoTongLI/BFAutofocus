import edu.univ_tlse3.BFAutofocus;
import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class Tests {

   @Parameterized.Parameters
   public static Collection<Object[]> prepareFiles() {
      String root = System.getProperty("user.dir") + "/src/main/resources/";
      ImagePlus tmpbfimg = IJ.openImage(root + "BF.tif");
      return Arrays.asList(new Object[][] {{tmpbfimg}});
   }
   @Parameterized.Parameter
   public ImagePlus  bfimg;

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
      int zsliceNb = bfimg.getDimensions()[3];
      double[] varArray = new double[zsliceNb];
      for (int i = 1; i< zsliceNb+1 ; i++){
         varArray[i-1] = bfimg.getStack().getProcessor(i).getStatistics().stdDev;
      }
      Assert.assertEquals(16, BFAutofocus.getZfocus(varArray),1);
   }
}
