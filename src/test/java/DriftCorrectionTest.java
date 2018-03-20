//import edu.univ_tlse3.DriftCorrection;
//import org.junit.Assert;
//import org.junit.Test;
//import org.junit.runner.RunWith;
//import org.junit.runners.Parameterized;
//import org.opencv.core.*;
//import java.io.IOException;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.Collection;
//
//@RunWith(Parameterized.class)
//public class DriftCorrectionTest {
//    @Parameterized.Parameters
//    public static Collection<Object[]> prepareFiles() throws IOException {
//        //need this function to load .so of opencv
//        nu.pattern.OpenCV.loadShared();
//        Mat img1 = null;
//        Mat img2 = null;
//        String root = System.getProperty("user.dir") + "/src/main/resources/";
//        img1 = DriftCorrection.readImage(root + "1-21.tif");
//        img2 = DriftCorrection.readImage(root + "2-21.tif");
//        return Arrays.asList(new Object[][] {{img1,img2}});
//    }
//    @Parameterized.Parameter
//    public Mat img1;
//
//    @Parameterized.Parameter(1)
//    public Mat img2;
//
//    @Test
//    public void assertImagesDims() {
//        Assert.assertEquals(img1.cols(), 2560);
//        Assert.assertEquals(img1.rows(), 2160);
//        Assert.assertEquals(img1.type(), CvType.CV_8UC1);
//        Assert.assertEquals(img1.channels(), 1);
//
//        Assert.assertEquals(img2.cols(), 2560);
//        Assert.assertEquals(img2.rows(), 2160);
//        Assert.assertEquals(img2.type(), CvType.CV_8UC1);
//        Assert.assertEquals(img2.channels(), 1);
//    }
//
//    @Test
//    public void assertImagesFeaturePoints() {
//        Integer detectorAlgo = DriftCorrection.DETECTORALGO;
//        MatOfKeyPoint keypoints1 = DriftCorrection.findKeypoints(img1, detectorAlgo);
//        Assert.assertEquals(keypoints1.toList().size(), 500);
//    }
//
//    @Test
//    public void assertDescriptors() {
//        Integer detectorAlgo = DriftCorrection.DETECTORALGO;
//        Mat img1_descriptors = DriftCorrection.calculDescriptors(img1,
//                DriftCorrection.findKeypoints(img1, detectorAlgo), DriftCorrection.DESCRIPTOREXTRACTOR);
//        Assert.assertEquals((long)img1_descriptors.get(0,1)[0], (long)186);
//        Assert.assertEquals((long)img1_descriptors.get(0,10)[0], (long)194);
//    }
//
//    @Test
//    public void assertDescriptorMatching() {
//        Integer descriptorMatcher = DriftCorrection.DESCRIPTORMATCHER;
//        Integer detectorAlgo = DriftCorrection.DETECTORALGO;
//        Mat img1_descriptors = DriftCorrection.calculDescriptors(img1,
//                DriftCorrection.findKeypoints(img1, detectorAlgo), DriftCorrection.DESCRIPTOREXTRACTOR);
//        Mat img2_descriptors = DriftCorrection.calculDescriptors(img2,
//                DriftCorrection.findKeypoints(img2, detectorAlgo), DriftCorrection.DESCRIPTOREXTRACTOR);
//        MatOfDMatch matcher =
//                DriftCorrection.matchingDescriptor(img1_descriptors, img2_descriptors, descriptorMatcher);
//        Assert.assertEquals((long)matcher.toArray()[0].distance, (long)390.6132);
//        Assert.assertEquals((long)matcher.toArray()[10].distance, (long)262.6132);
//    }
//
//    @Test
//    public void assertFiltering() {
//        Integer descriptorMatcher = DriftCorrection.DESCRIPTORMATCHER;
//        Integer detectorAlgo = DriftCorrection.DETECTORALGO;
//        MatOfKeyPoint keypoint1 = DriftCorrection.findKeypoints(img1, detectorAlgo);
//        MatOfKeyPoint keypoint2 = DriftCorrection.findKeypoints(img2, detectorAlgo);
//        Mat img1_descriptors = DriftCorrection.calculDescriptors(img1, keypoint1, DriftCorrection.DESCRIPTOREXTRACTOR);
//        Mat img2_descriptors = DriftCorrection.calculDescriptors(img2, keypoint2, DriftCorrection.DESCRIPTOREXTRACTOR);
//        MatOfDMatch matcher = DriftCorrection.matchingDescriptor(img1_descriptors, img2_descriptors, descriptorMatcher);
//
//        ArrayList<DMatch> listOfGoodMatches = DriftCorrection.selectGoodMatches(matcher, keypoint1, keypoint2,
//                DriftCorrection.UMPERMIN, DriftCorrection.UMPERPIX, DriftCorrection.INTERVALINMIN);
//        Assert.assertEquals(113, listOfGoodMatches.size());
//        Assert.assertEquals((long) 341.40356,(long) listOfGoodMatches.get(0).distance);
//        Assert.assertEquals((long) 289.40356,(long) listOfGoodMatches.get(15).distance);
//        Assert.assertEquals((long) 306.40356,(long) listOfGoodMatches.get(55).distance);
//
//    }
//}
