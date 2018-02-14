import ij.IJ;
import ij.ImagePlus;
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
      ImagePlus tmpfluoimg = IJ.openImage(root + "FLUO.tif");
      return Arrays.asList(new Object[][] {{tmpbfimg, tmpfluoimg}});
   }
   @Parameterized.Parameter
   public ImagePlus  bfimg;

   @Parameterized.Parameter(1)
   public ImagePlus  fluoimg;

   @Test
   public void show(){
      bfimg.show();
      fluoimg.show();
//      OpService ops = new DefaultOpService();
//      FinalInterval finalInterval = new FinalInterval(0,0,5,5);
//
//      RandomAccessibleInterval<UnsignedByteType> view = Views.interval(bfimg.getImg(), finalInterval);
//      ImageJFunctions.show( view );
//      logger.info((int) ops.run(Ops.Math.Add.class, 1,2) + "");
//      int x = (int) ops.run("math.add", 1,1);
//      System.out.println(x);
//      RandomAccessibleInterval
//      logger.info(x + "");
//      ImageJFunctions.show( img. );
      try {
         Thread.sleep(100000);
      } catch (InterruptedException e) {
         e.printStackTrace();
      }
   }
}
