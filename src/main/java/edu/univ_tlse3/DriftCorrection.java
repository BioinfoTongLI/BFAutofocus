package edu.univ_tlse3;

import ij.ImagePlus;
import org.apache.commons.math3.stat.descriptive.rank.Median;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.*;

import static org.opencv.features2d.Features2d.NOT_DRAW_SINGLE_POINTS;

public class DriftCorrection {

//    public static double UMPERMIN = 50;
//    public static double INTERVALINMIN = 0;
//    public static final double UMPERPIX = 0.108;
    public static final Integer DETECTORALGO = FeatureDetector.BRISK;
    public static final Integer DESCRIPTOREXTRACTOR = DescriptorExtractor.ORB;
    public static final Integer DESCRIPTORMATCHER = DescriptorMatcher.FLANNBASED;
    public static final Integer DETECTORALGO_ORB = FeatureDetector.ORB;
    public static final Integer DESCRIPTOREXTRACTOR_ORB = DescriptorExtractor.ORB;

    // Read images from path
    public static Mat readImage(String pathOfImage) {
        Mat img = Imgcodecs.imread(pathOfImage, CvType.CV_16UC1);
        Mat img1 = new Mat(img.cols(), img.rows(), CvType.CV_8UC1);
        img.convertTo(img1, CvType.CV_8UC1, BFAutofocus.alpha);
        Mat img2 = equalizeImages(img1);
        return img2;
    }

    public static Mat equalizeImages(Mat img) {
        Mat imgEqualized = new Mat(img.cols(), img.rows(), img.type());
        Imgproc.equalizeHist(img, imgEqualized);
        return imgEqualized;
    }

    public static MatOfKeyPoint findKeypoints(Mat img, int detectorType) {
        MatOfKeyPoint keypoints = new MatOfKeyPoint();
        FeatureDetector featureDetector = FeatureDetector.create(detectorType);
        featureDetector.detect(img, keypoints);
        return keypoints;
    }

    public static Mat calculDescriptors(Mat img, MatOfKeyPoint keypoints, int descriptorType) {
        Mat img_descript = new Mat();
        DescriptorExtractor extractor = DescriptorExtractor.create(descriptorType);
        extractor.compute(img, keypoints, img_descript);
        return img_descript;
    }

    public static MatOfDMatch matchingDescriptor(Mat img1_calcul_descriptors, Mat img2_calcul_descriptors, int descriptorMatcherType) {
        MatOfDMatch matcher = new MatOfDMatch();
        DescriptorMatcher matcherDescriptor = DescriptorMatcher.create(descriptorMatcherType);
        Mat img1_descriptor = convertMatDescriptorToCV32F(img1_calcul_descriptors);
        Mat img2_descriptor = convertMatDescriptorToCV32F(img2_calcul_descriptors);
        matcherDescriptor.match(img1_descriptor, img2_descriptor, matcher);
        return matcher;
    }

    //Calculate distance (in um) between each pair of points :
    public static ArrayList<Double> getDistancesInUm(MatOfDMatch matcher, MatOfKeyPoint keyPoint1, MatOfKeyPoint keyPoint2, double calibration) {
        DMatch[] matcherArray = matcher.toArray();
        KeyPoint[] keypoint1Array = keyPoint1.toArray();
        KeyPoint[] keypoint2Array = keyPoint2.toArray();
        ArrayList<Double> listOfDistances = new ArrayList<>();
        double x;
        double x1;
        double x2;
        double y;
        double y1;
        double y2;
        double d;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (int i =0; i < matcherArray.length; i++) {
            int dmQuery = matcherArray[i].queryIdx;
            int dmTrain = matcherArray[i].trainIdx;

            x1 = keypoint1Array[dmQuery].pt.x;
            x2 = keypoint2Array[dmTrain].pt.x;
            x = (x2 - x1) * calibration;

            y1 = keypoint1Array[dmQuery].pt.y;
            y2 = keypoint2Array[dmTrain].pt.y;
            y = (y2 - y1) * calibration;

            d = Math.hypot(x, y);
            listOfDistances.add(d);
        }
        //Get min and max distances
        for (int i = 0; i < listOfDistances.size(); i++) {
            if (listOfDistances.get(i) < min) {
                min = listOfDistances.get(i);
            }
            if (listOfDistances.get(i) > max) {
                max = listOfDistances.get(i);
            }
        }
        System.out.println("Min dist : " + min);
        System.out.println("Max dist : " + max);
        return listOfDistances;
    }

    public  static ArrayList<DMatch> selectGoodMatches(MatOfDMatch matcher, MatOfKeyPoint keyPoint1, MatOfKeyPoint keyPoint2, double umPerStep, double calibration, double intervalInMin) {
        DMatch[] matcherArray = matcher.toArray();
        ArrayList<Double> listOfDistancesInUm = getDistancesInUm(matcher, keyPoint1, keyPoint2, calibration);
//        ArrayList<Double> listOfDistancesInUm = new ArrayList<>();
        ArrayList<DMatch> good_matchesList = new ArrayList<>();
        for (int i = 0; i < matcherArray.length; i++) {
//            Double distanceInUm = listOfDistancesInPx.get(i) * calibration;
//            listOfDistancesInUm.add(distanceInUm);
            if (listOfDistancesInUm.get(i) <= umPerStep/intervalInMin) {
                good_matchesList.add(matcherArray[i]);
            }
        }
        return good_matchesList;
    }

    static ArrayList<Float> getGoodMatchesXCoordinates(MatOfKeyPoint keypoints, ArrayList<DMatch> good_matchesList, Boolean isReferenceImage) {
        ArrayList<Float> img_xList = new ArrayList<Float>();
        KeyPoint[] keypointsArray1 = keypoints.toArray();
        float x;
        int id;
        for (int i = 0; i < good_matchesList.size() ; i++) {
            if (isReferenceImage) {
                id = good_matchesList.get(i).queryIdx;
            } else {
                id = good_matchesList.get(i).trainIdx;
            }
            x = (float) keypointsArray1[id].pt.x;
            img_xList.add(x);
        }
        return img_xList;
    }

    static ArrayList<Float> getGoodMatchesYCoordinates(MatOfKeyPoint keypoints, ArrayList<DMatch> good_matchesList, Boolean isReferenceImage) {
        ArrayList<Float> img_yList = new ArrayList<Float>();
        KeyPoint[] keypointsArray1 = keypoints.toArray();
        float y;
        int id;
        for (int i = 0; i < good_matchesList.size() ; i++) {
            if (isReferenceImage) {
                id = good_matchesList.get(i).queryIdx;
            } else {
                id = good_matchesList.get(i).trainIdx;
            }
            y = (float) keypointsArray1[id].pt.y;
            img_yList.add(y);
        }
        return img_yList;
    }

    static Float getMeanDisplacement(ArrayList<Float> img1_coordinates, ArrayList<Float> img2_coordinates) {
        int totalNumberOfX = img1_coordinates.size();
        float sumXDistancesCoordinates = 0;
        float meanXDifferencesCoordinates;
        double[] xDistances = new double[img1_coordinates.size()];
        for (int i = 0; i < img1_coordinates.size(); i++) {
            float xDistance = img2_coordinates.get(i) - img1_coordinates.get(i);
            sumXDistancesCoordinates += xDistance;
            xDistances[i] = xDistance;
        }
        meanXDifferencesCoordinates = sumXDistancesCoordinates/totalNumberOfX;

        return meanXDifferencesCoordinates;
    }

    static double getMedianDisplacement(ArrayList<Float> img1_coordinates, ArrayList<Float> img2_coordinates) {
        double[] distances = new double[img1_coordinates.size()];
        for (int i = 0; i < img1_coordinates.size(); i++) {
            float distance = img2_coordinates.get(i) - img1_coordinates.get(i);
            distances[i] = distance;
        }
        Median median = new Median();
        double medianValue = median.evaluate(distances);
        System.out.println("Median : " + medianValue);

        return medianValue;
    }

    static List<Integer> getModeDisplacement(ArrayList<Float> img1_coordinates, ArrayList<Float> img2_coordinates) {
        //Initializations
        double[] distances = new double[img1_coordinates.size()];
        List<Integer> modes = new ArrayList<>();
        Map<Integer, Integer> countMap = new HashMap<Integer, Integer>();
        int max  = (int) Double.MIN_VALUE;
        //Calcul difference between img1 and img2 coordinates
        for (int i = 0; i < img1_coordinates.size(); i++) {
            float distance = img2_coordinates.get(i) - img1_coordinates.get(i);
            distances[i] = distance;
        }

        for (int n = 0; n < distances.length; n++) {
            //Create dictionary of values and associated count
            int count = 0;
            if (countMap.containsKey(n)) {
                count = countMap.get(n) + 1;
            } else {
                count = 1;
            }
            countMap.put(n, count);

            //Determine if the count is the max or not
            if (count > max) {
                max = count;
            }
        }

        for (Map.Entry<Integer, Integer> tuple : countMap.entrySet()) {
            if (tuple.getValue() == max) {
                modes.add(tuple.getKey());
            }
        }

        return modes;
    }

    static Float getXVariance(ArrayList<Float> img1_xCoordinates, ArrayList<Float> img2_xCoordinates, Float meanXDisplacement) {
        int totalNumberOfX = img1_xCoordinates.size();
        float sumDiffSquared = 0;
        float varianceX;
        for (int i = 0; i < img1_xCoordinates.size(); i++) {
            float xDiff = img2_xCoordinates.get(i) - img1_xCoordinates.get(i);
            sumDiffSquared += Math.pow(xDiff - meanXDisplacement, 2);
        }
        varianceX = sumDiffSquared/totalNumberOfX;
        return  varianceX;
    }

    static Float getYVariance(ArrayList<Float> img1_yCoordinates, ArrayList<Float> img2_yCoordinates, Float meanYDisplacement) {
        int totalNumberOfY = img1_yCoordinates.size();
        float sumDiffSquared = 0;
        float varianceY;
        for (int i = 0; i < img1_yCoordinates.size(); i++) {
            float yDiff = img2_yCoordinates.get(i) - img1_yCoordinates.get(i);
            sumDiffSquared += Math.pow(yDiff - meanYDisplacement, 2);
        }
        varianceY = sumDiffSquared/totalNumberOfY;
        return varianceY;
    }

    //Convert Descriptors to CV_32F
    static  Mat convertMatDescriptorToCV32F(Mat descriptor) {
        Mat descriptor32F = new Mat(descriptor.cols(), descriptor.rows(), CvType.CV_32F);
        if (descriptor.type() != CvType.CV_32F) {
            descriptor.convertTo(descriptor32F, CvType.CV_32F);
        }
        return descriptor32F;
    }

    //Method to not filter matches
    static ArrayList<DMatch> convertMatOfMatcherToDMatch(MatOfDMatch matcher) {
        List<DMatch> matcherList = matcher.toList();
        ArrayList<DMatch> matcherArrayList = new ArrayList<DMatch>(matcherList.size());
        return matcherArrayList;
    }
    // CONVERTERS
    // Convert 8bits Mat images to Buffered
    static BufferedImage convertMatCV8UC3ToBufferedImage(Mat m) {
        int type = BufferedImage.TYPE_3BYTE_BGR;
        int bufferSize = m.channels() * m.cols() * m.rows();
        byte[] b = new byte[bufferSize];
        m.get(0, 0 ,b);
        BufferedImage img = new BufferedImage(m.cols(), m.rows(), type);
        img.getRaster().setDataElements(0, 0, m.cols(), m.rows(), b);
        return img;
    }

    static BufferedImage convertMatCV8UC1ToBufferedImage(Mat m) {
        int type = BufferedImage.TYPE_BYTE_GRAY;
        int bufferSize = m.channels() * m.cols() * m.rows();
        byte[] b = new byte[bufferSize];
        m.get(0, 0 ,b);
        BufferedImage img = new BufferedImage(m.cols(), m.rows(), type);
        img.getRaster().setDataElements(0, 0, m.cols(), m.rows(), b);
        return img;
    }

    // Convert double Array to byte Array
    //https://stackoverflow.com/questions/15533854/converting-byte-array-to-double-array
    static byte[] toByteArray(double[] doubleArray){
        int times = Double.SIZE / Byte.SIZE;
        byte[] bytes = new byte[doubleArray.length * times];
        for(int i=0;i<doubleArray.length;i++){
            ByteBuffer.wrap(bytes, i*times, times).putDouble(doubleArray[i]);
        }
        return bytes;
    }

    // Convert 64bits Mat images to Buffered
    static BufferedImage convertMatCV64ToBufferedImage(Mat m) {
        int type = BufferedImage.TYPE_3BYTE_BGR;
        int bufferSize = m.channels() * m.cols() * m.rows();
        double[] d = new double[bufferSize];
        m.get(0, 0, d);
        BufferedImage img = new BufferedImage(m.cols(), m.rows(), type);
        byte[] b = toByteArray(d);
        img.getRaster().getDataElements(0, 0, m.cols(), m.rows(), b);
        return img;
    }

    static Mat listToMat(ArrayList<DMatch> list) {
        MatOfDMatch mat = new MatOfDMatch();
        DMatch[] array = list.toArray(new DMatch[list.size()]);
        mat.fromArray(array);
        return mat;
    }

    //Display images with ImageJ, giving a title to image
    static void displayImageIJ(String titleOfImage, Mat img) {
        ImagePlus imgp = new ImagePlus();
        if (img.type() == CvType.CV_8UC3) {imgp = new ImagePlus(titleOfImage, convertMatCV8UC3ToBufferedImage(img));}
        else if (img.type() == CvType.CV_64FC1) {imgp = new ImagePlus(titleOfImage, convertMatCV64ToBufferedImage(img));}
        else if (img.type() == CvType.CV_8UC1) {imgp = new ImagePlus(titleOfImage, convertMatCV8UC1ToBufferedImage(img));}
        else{
            try {
                throw new Exception("Unknown image type");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        imgp.show();
    }

    static Mat drawGoodMatches(Mat img1, Mat img2, MatOfKeyPoint keypoints1, MatOfKeyPoint keypoints2, ArrayList<DMatch> good_matchesList) {
        Mat good_matches = listToMat(good_matchesList);
        Mat imgGoodMatches = new Mat();
        MatOfByte matchesMask = new MatOfByte();
        Features2d.drawMatches(img1, keypoints1, img2, keypoints2, (MatOfDMatch) good_matches, imgGoodMatches, Scalar.all(-1), Scalar.all(0.5), matchesMask, NOT_DRAW_SINGLE_POINTS);
        return imgGoodMatches;
    }

    public static double[] driftCorrection(Mat img1, Mat img2, double calibration, double intervalInMin, double umPerStep,
                                           Integer detectorAlgo, Integer descriptorExtractor, Integer descriptorMatcher) {
        long startTime = new Date().getTime();

        /* 1 - Detect keypoints */
        MatOfKeyPoint keypoints1 = findKeypoints(img1, detectorAlgo);
        MatOfKeyPoint keypoints2 = findKeypoints(img2, detectorAlgo);
        System.out.println("Keypoints img ref : " + keypoints1.rows());
        System.out.println("Keypoints img 2 : " + keypoints2.rows());

        /* 2 - Calculate descriptors */
        Mat img1_descriptors = calculDescriptors(img1, keypoints1, descriptorExtractor);
        Mat img2_descriptors = calculDescriptors(img2, keypoints2, descriptorExtractor);

        if(img1_descriptors.empty()) {
            System.out.println("Descriptor ref image empty");
        }
        if(img2_descriptors.empty()){
            System.out.println("Descriptor image 2 empty");
        }

        /* 3 - Matching descriptor */
        MatOfDMatch matcher = matchingDescriptor(img1_descriptors, img2_descriptors, descriptorMatcher);
        System.out.println("Number of Matches : " + matcher.rows());

        /* 4 - Select and display Good Matches */
        ArrayList<DMatch> good_matchesList = selectGoodMatches(matcher, keypoints1, keypoints2, umPerStep, calibration, intervalInMin);
        System.out.println("Number of Good Matches : " + good_matchesList.size());

//        Mat imgGoodMatches = drawGoodMatches(img1, img2, keypoints1, keypoints2, good_matchesList);
//        displayImageIJ("Good Matches", imgGoodMatches);

        /* 5 - Get coordinates of GoodMatches Keypoints */
        ArrayList<Float> img1_keypoints_xCoordinates = getGoodMatchesXCoordinates(keypoints1, good_matchesList,true);
        ArrayList<Float> img1_keypoints_yCoordinates = getGoodMatchesYCoordinates(keypoints1, good_matchesList, true);

        ArrayList<Float> img2_keypoints_xCoordinates = getGoodMatchesXCoordinates(keypoints2, good_matchesList,false);
        ArrayList<Float> img2_keypoints_yCoordinates = getGoodMatchesYCoordinates(keypoints2, good_matchesList, false);

        /* 6 - Get X and Y mean displacements */
        float meanXdisplacement = getMeanDisplacement(img1_keypoints_xCoordinates, img2_keypoints_xCoordinates);
        float meanYdisplacement = getMeanDisplacement(img1_keypoints_yCoordinates, img2_keypoints_yCoordinates);
        System.out.println("X mean displacement : " + meanXdisplacement);
        System.out.println("Y mean displacement : " + meanYdisplacement + "\n");

        /* Calculate some other statistics */
        double medianXDisplacement = getMedianDisplacement(img1_keypoints_xCoordinates, img2_keypoints_xCoordinates);
        double medianYDisplacement = getMedianDisplacement(img1_keypoints_yCoordinates, img2_keypoints_yCoordinates);

        List<Integer> modesXDisplacements = getModeDisplacement(img1_keypoints_xCoordinates, img2_keypoints_xCoordinates);
        List<Integer> modesYDisplacements = getModeDisplacement(img1_keypoints_yCoordinates, img2_keypoints_yCoordinates);

        long endTime = new Date().getTime();
        long algorithmDuration = endTime - startTime;

        return new double[]{meanXdisplacement, meanYdisplacement, matcher.rows(), good_matchesList.size(), algorithmDuration,
                medianXDisplacement, medianYDisplacement};
    }
}

