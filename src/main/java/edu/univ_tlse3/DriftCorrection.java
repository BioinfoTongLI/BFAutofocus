package edu.univ_tlse3;

import clojure.lang.IFn;
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
        img.convertTo(img1, CvType.CV_8UC1);//, BFAutofocus.alpha);
        Mat img2 = equalizeImages(img1);
        return img2;
    }

    static Mat equalizeImages(Mat img) {
        Mat imgEqualized = new Mat(img.cols(), img.rows(), img.type());
        Imgproc.equalizeHist(img, imgEqualized);
        return imgEqualized;
    }

    static MatOfKeyPoint findKeypoints(Mat img, int detectorType) {
        MatOfKeyPoint keypoints = new MatOfKeyPoint();
        FeatureDetector featureDetector = FeatureDetector.create(detectorType);
        featureDetector.detect(img, keypoints);
        return keypoints;
    }

    static Mat calculDescriptors(Mat img, MatOfKeyPoint keypoints, int descriptorType) {
        Mat img_descript = new Mat();
        DescriptorExtractor extractor = DescriptorExtractor.create(descriptorType);
        extractor.compute(img, keypoints, img_descript);
        return img_descript;
    }

    static MatOfDMatch matchingDescriptor(Mat img1_calcul_descriptors, Mat img2_calcul_descriptors, int descriptorMatcherType) {
        MatOfDMatch matcher = new MatOfDMatch();
        DescriptorMatcher matcherDescriptor = DescriptorMatcher.create(descriptorMatcherType);
        Mat img1_descriptor = convertMatDescriptorToCV32F(img1_calcul_descriptors);
        Mat img2_descriptor = convertMatDescriptorToCV32F(img2_calcul_descriptors);
        matcherDescriptor.match(img1_descriptor, img2_descriptor, matcher);
        return matcher;
    }

    //Calculate distance (in um) between each pair of points :
    static Map getDistancesInUm(MatOfDMatch matcher, MatOfKeyPoint keyPoint1, MatOfKeyPoint keyPoint2, double calibration) {
        DMatch[] matcherArray = matcher.toArray();
        KeyPoint[] keypoint1Array = keyPoint1.toArray();
        KeyPoint[] keypoint2Array = keyPoint2.toArray();
        Map globalListOfDistances = new HashMap<String, ArrayList<Double>>();
        ArrayList<Double> listOfDistances = new ArrayList<>();
        ArrayList<Double> listOfDistancesX = new ArrayList<>();
        ArrayList<Double> listOfDistancesY = new ArrayList<>();
        double x;
        double x1;
        double x2;
        double y;
        double y1;
        double y2;
        double d;
        for (int i =0; i < matcherArray.length; i++) {
            int dmQuery = matcherArray[i].queryIdx;
            int dmTrain = matcherArray[i].trainIdx;

            x1 = keypoint1Array[dmQuery].pt.x;
            x2 = keypoint2Array[dmTrain].pt.x;
            x = (x2 - x1) * calibration;
            listOfDistancesX.add(x);

            y1 = keypoint1Array[dmQuery].pt.y;
            y2 = keypoint2Array[dmTrain].pt.y;
            y = (y2 - y1) * calibration;
            listOfDistancesY.add(y);

            d = Math.hypot(x, y);
            listOfDistances.add(d);
        }

        globalListOfDistances.put("distances", listOfDistances);
        globalListOfDistances.put("xDistances", listOfDistancesX);
        globalListOfDistances.put("yDistances", listOfDistancesY);

        return globalListOfDistances;
    }

    static ArrayList<Integer> getGoodMatchesIndex(MatOfDMatch matcher, MatOfKeyPoint keyPoint1, MatOfKeyPoint keyPoint2, double umPerStep, double calibration, double intervalInMin){
        ArrayList<Integer> goodMatchesIndexArray = new ArrayList<>();
        ArrayList<Double> listOfDistancesInUm = getSingleListOfDistancesInUm("distances", matcher, keyPoint1, keyPoint2, calibration);
        for (int i = 0; i < listOfDistancesInUm.size(); i++) {
            if (listOfDistancesInUm.get(i) <= umPerStep/intervalInMin) {
                goodMatchesIndexArray.add(i);
            }
        }
        return goodMatchesIndexArray;
    }

    static ArrayList<DMatch> getGoodMatchesValues(MatOfDMatch matcher, MatOfKeyPoint keyPoint1, MatOfKeyPoint keyPoint2, double umPerStep, double calibration, double intervalInMin) {
        DMatch[] matcherArray = matcher.toArray();
        ArrayList<Integer> listOfGoodMatchesIndex = getGoodMatchesIndex(matcher, keyPoint1, keyPoint2, umPerStep, calibration, intervalInMin);
        ArrayList<DMatch> listOfGoodMatchesValues = new ArrayList<>();
        for (int i = 0; i < listOfGoodMatchesIndex.size(); i++) {
            listOfGoodMatchesValues.add(matcherArray[listOfGoodMatchesIndex.get(i)]);
        }
        return listOfGoodMatchesValues;
    }

    static ArrayList<Double> getSingleListOfDistancesInUm(String keyToSearch, MatOfDMatch matcher, MatOfKeyPoint keyPoint1, MatOfKeyPoint keyPoint2, double calibration){
        Map globallistOfDistances = getDistancesInUm(matcher, keyPoint1, keyPoint2, calibration);
        ArrayList<Double> listOfDistances = (ArrayList<Double>) globallistOfDistances.get(keyToSearch);
        return listOfDistances;
    }

    static ArrayList<Double> getGoodMatchesDistances(String keyToSearch, ArrayList<Integer> listOfGoodMatchesIndex, MatOfDMatch matcher, MatOfKeyPoint keyPoint1, MatOfKeyPoint keyPoint2, double calibration){
        ArrayList<Double> uniqueListOfDistance = getSingleListOfDistancesInUm(keyToSearch, matcher, keyPoint1, keyPoint2,calibration);
        ArrayList<Double> listOfGoodMatchesDistances = new ArrayList<>();
        for (int i = 0; i < listOfGoodMatchesIndex.size(); i++) {
            listOfGoodMatchesDistances.add(uniqueListOfDistance.get(listOfGoodMatchesIndex.get(i)));
        }
        return listOfGoodMatchesDistances;
    }

    static double getMin(ArrayList<Double> listOfValues) {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < listOfValues.size(); i++) {
            if (listOfValues.get(i) < min) {
                min = listOfValues.get(i);
            }
        }
        return min;
    }

//    static ArrayList<Float> getGoodMatchesXCoordinates(MatOfKeyPoint keypoints, ArrayList<DMatch> good_matchesList, Boolean isReferenceImage) {
//        ArrayList<Float> img_xList = new ArrayList<Float>();
//        KeyPoint[] keypointsArray1 = keypoints.toArray();
//        float x;
//        int id;
//        for (int i = 0; i < good_matchesList.size() ; i++) {
//            if (isReferenceImage) {
//                id = good_matchesList.get(i).queryIdx;
//            } else {
//                id = good_matchesList.get(i).trainIdx;
//            }
//            x = (float) keypointsArray1[id].pt.x;
//            img_xList.add(x);
//        }
//        return img_xList;
//    }
//
//    static ArrayList<Float> getGoodMatchesYCoordinates(MatOfKeyPoint keypoints, ArrayList<DMatch> good_matchesList, Boolean isReferenceImage) {
//        ArrayList<Float> img_yList = new ArrayList<Float>();
//        KeyPoint[] keypointsArray1 = keypoints.toArray();
//        float y;
//        int id;
//        for (int i = 0; i < good_matchesList.size() ; i++) {
//            if (isReferenceImage) {
//                id = good_matchesList.get(i).queryIdx;
//            } else {
//                id = good_matchesList.get(i).trainIdx;
//            }
//            y = (float) keypointsArray1[id].pt.y;
//            img_yList.add(y);
//        }
//        return img_yList;
//    }
//
//    static Float getMean(ArrayList<Float> img1_coordinates, ArrayList<Float> img2_coordinates) {
//        int totalNumberOfX = img1_coordinates.size();
//        float sumXDistancesCoordinates = 0;
//        float meanXDifferencesCoordinates;
//        double[] xDistances = new double[img1_coordinates.size()];
//        for (int i = 0; i < img1_coordinates.size(); i++) {
//            float xDistance = img2_coordinates.get(i) - img1_coordinates.get(i);
//            sumXDistancesCoordinates += xDistance;
//            xDistances[i] = xDistance;
//        }
//        meanXDifferencesCoordinates = sumXDistancesCoordinates/totalNumberOfX;
//
//        return meanXDifferencesCoordinates;
//    }

    static Float getMean(ArrayList<Double> listOfValues) {
        int totalNumberOfX = listOfValues.size();
        float sumXDistancesCoordinates = 0;
        float meanXDifferencesCoordinates;
        double[] xDistances = new double[listOfValues.size()];
        for (int i = 0; i < listOfValues.size(); i++) {
            sumXDistancesCoordinates += listOfValues.get(i);
            xDistances[i] = listOfValues.get(i);
        }
        meanXDifferencesCoordinates = sumXDistancesCoordinates/totalNumberOfX;
        return meanXDifferencesCoordinates;
    }

//    static double getAutoMedian(ArrayList<Float> img1_coordinates, ArrayList<Float> img2_coordinates) {
//        double[] distances = new double[img1_coordinates.size()];
//        for (int i = 0; i < img1_coordinates.size(); i++) {
//            float distance = img2_coordinates.get(i) - img1_coordinates.get(i);
//            distances[i] = distance;
//        }
//        Median median = new Median();
//        double medianValue = median.evaluate(distances);
//        System.out.println("Median : " + medianValue);
//
//        return medianValue;
//    }

    static double getAutoMedian(ArrayList<Double> listOfValues) {
        double[] distancesArray = new double[listOfValues.size()];
        for (int i = 0; i < listOfValues.size(); i++) {
            distancesArray[i] = listOfValues.get(i);
        }
        Median median = new Median();
        double medianValue = median.evaluate(distancesArray);
        return medianValue;
    }

    static double getManualMedian(ArrayList<Double> listOfValues) {
//        ArrayList<Double> sortedListOfValues =
        Collections.sort(listOfValues);
        int middle = listOfValues.size()/2;
        if(listOfValues.size()%2 == 1){
            return listOfValues.get(middle);
        } else {
            double meanMiddle = (listOfValues.get(middle-1) + listOfValues.get(middle))/2.0;
            return  meanMiddle;
        }
    }

    static List<Integer> getModesDisplacements(ArrayList<Double> listOfValues) {
        //Initializations
        double[] distances = new double[listOfValues.size()];
        List<Integer> modes = new ArrayList<>();
        Map<Integer, Integer> countMap = new HashMap<Integer, Integer>();
        int max  = (int) Double.MIN_VALUE;
        //Calcul difference between img1 and img2 coordinates
        for (int i = 0; i < listOfValues.size(); i++) {
            distances[i] = listOfValues.get(i);
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

    static double getMode(ArrayList<Double> listOfValues){
        double maxValue = 0;
        int maxCount = 0;
        for (int i =0; i < listOfValues.size(); i++){
            int count = 0;
            for (int j = 0; j < listOfValues.size(); j ++){
                if(listOfValues.get(j) == listOfValues.get(i)){
                    count += 1;
                }
            }
            if (count > maxCount){
                maxCount = count;
                maxValue = listOfValues.get(i);
            }
        }
        return maxValue;
    }

    static Float getVariance(ArrayList<Double> listOfValues) {
        int totalNumberOfValues = listOfValues.size();
        float sumDiffSquared = 0;
        float variance;
        float mean = getMean(listOfValues);
        for (int i = 0; i < listOfValues.size(); i++) {
            sumDiffSquared += Math.pow(listOfValues.get(i) - mean, 2);
        }
        variance = sumDiffSquared/totalNumberOfValues;
        return  variance;
    }

    //Method to not filter matches
    static ArrayList<DMatch> convertMatOfMatcherToDMatch(MatOfDMatch matcher) {
        List<DMatch> matcherList = matcher.toList();
        ArrayList<DMatch> matcherArrayList = new ArrayList<DMatch>(matcherList.size());
        return matcherArrayList;
    }

    // CONVERTERS
    //Convert Descriptors to CV_32F
    static  Mat convertMatDescriptorToCV32F(Mat descriptor) {
        Mat descriptor32F = new Mat(descriptor.cols(), descriptor.rows(), CvType.CV_32F);
        if (descriptor.type() != CvType.CV_32F) {
            descriptor.convertTo(descriptor32F, CvType.CV_32F);
        }
        return descriptor32F;
    }
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

    //Convert an ArrayList to OpenCV Mat
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

    //*********************************//
    //******* Main method ************//
    //*******************************//
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
        ArrayList<DMatch> good_matchesList = getGoodMatchesValues(matcher, keypoints1, keypoints2, umPerStep, calibration, intervalInMin);
        System.out.println("Number of Good Matches : " + good_matchesList.size());

//        Mat imgGoodMatches = drawGoodMatches(img1, img2, keypoints1, keypoints2, good_matchesList);
//        displayImageIJ("Good Matches", imgGoodMatches);

//        /* 5 - Get coordinates of GoodMatches Keypoints */
//        ArrayList<Float> img1_keypoints_xCoordinates = getGoodMatchesXCoordinates(keypoints1, good_matchesList,true);
//        ArrayList<Float> img1_keypoints_yCoordinates = getGoodMatchesYCoordinates(keypoints1, good_matchesList, true);
//
//        ArrayList<Float> img2_keypoints_xCoordinates = getGoodMatchesXCoordinates(keypoints2, good_matchesList,false);
//        ArrayList<Float> img2_keypoints_yCoordinates = getGoodMatchesYCoordinates(keypoints2, good_matchesList, false);

        /* 5 - Get Good Matches Indexes and distances */
        ArrayList<Integer> listOfGoodMatchesIndex = getGoodMatchesIndex(matcher, keypoints1, keypoints2, umPerStep, calibration, intervalInMin);
        ArrayList<Double> goodMatchesXDistances = getGoodMatchesDistances("xDistances", listOfGoodMatchesIndex, matcher, keypoints1, keypoints2, calibration);
        ArrayList<Double> goodMatchesYDistances = getGoodMatchesDistances("yDistances", listOfGoodMatchesIndex, matcher, keypoints1, keypoints2, calibration);

        /* Calculate statistics */
        float meanXdisplacement = getMean(goodMatchesXDistances);
        float meanYdisplacement = getMean(goodMatchesYDistances);
        System.out.println("X mean displacement : " + meanXdisplacement);
        System.out.println("Y mean displacement : " + meanYdisplacement + "\n");

        double minXDisplacement = getMin(goodMatchesXDistances);
        double minYDisplacement = getMin(goodMatchesYDistances);

        double autoMedianXDisplacement = getAutoMedian(goodMatchesXDistances);
        double autoMedianYDisplacement = getAutoMedian(goodMatchesYDistances);

        double manualMedianXDisplacement = getManualMedian(goodMatchesXDistances);
        double manualMedianYDisplacement = getManualMedian(goodMatchesYDistances);

        double modeXDisplacement = getMode(goodMatchesXDistances);
        double modeYDisplacement = getMode(goodMatchesYDistances);

        double varianceXDisplacement = getVariance(goodMatchesXDistances);
        double varianceYDisplacement = getVariance(goodMatchesYDistances);


        long endTime = new Date().getTime();
        long algorithmDuration = endTime - startTime;

        return new double[]{meanXdisplacement, meanYdisplacement, matcher.rows(), good_matchesList.size(), algorithmDuration,
                autoMedianXDisplacement, autoMedianYDisplacement, manualMedianXDisplacement, manualMedianYDisplacement,
                minXDisplacement, minYDisplacement, modeXDisplacement, modeYDisplacement,
                varianceXDisplacement, varianceYDisplacement
        };
    }
}

