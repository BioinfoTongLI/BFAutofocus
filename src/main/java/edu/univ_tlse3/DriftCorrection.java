package edu.univ_tlse3;

import org.opencv.core.*;
import org.opencv.features2d.DescriptorExtractor;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.FeatureDetector;

import java.util.ArrayList;

public class DriftCorrection {

    public static final double UMPERMIN = 0.5;
    public static final double INTERVALINMIN = 2;
    public static final double UMPERPIX = 0.065;
    public static final Integer DETECTORALGO = FeatureDetector.ORB;
    public static final Integer DESCRIPTOREXTRACTOR = DescriptorExtractor.ORB;
    public static final Integer DESCRIPTORMATCHER = DescriptorMatcher.FLANNBASED;

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

    //Calculate distance (in pixels) between each pair of points :
    public static ArrayList<Double> getDistances(MatOfDMatch matcher, MatOfKeyPoint keyPoint1, MatOfKeyPoint keyPoint2) {
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
        for (int i =0; i < matcherArray.length; i++) {
            int dmQuery = matcherArray[i].queryIdx;
            int dmTrain = matcherArray[i].trainIdx;

            x1 = keypoint1Array[dmQuery].pt.x;
            x2 = keypoint2Array[dmTrain].pt.x;
            x = x2 - x1;

            y1 = keypoint1Array[dmQuery].pt.y;
            y2 = keypoint2Array[dmTrain].pt.y;
            y = y2 - y1;

            d = Math.hypot(x, y); // /0.065;
            listOfDistances.add(d);
        }
        return listOfDistances;
    }

    public  static ArrayList<DMatch> selectGoodMatches(MatOfDMatch matcher, MatOfKeyPoint keyPoint1, MatOfKeyPoint keyPoint2, double umPerMin, double umPerPixel, double intervalInMin) {
        DMatch[] matcherArray = matcher.toArray();
        ArrayList<Double> listOfDistances = getDistances(matcher, keyPoint1, keyPoint2);
        ArrayList<DMatch> good_matchesList = new ArrayList<>();
        for (int i = 0; i < matcherArray.length; i++) {
            if (listOfDistances.get(i) <= (umPerMin/umPerPixel)* intervalInMin) {
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

    static Float getMeanXDisplacement(ArrayList<Float> img1_xCoordinates, ArrayList<Float> img2_xCoordinates) {
        int totalNumberOfX = img1_xCoordinates.size();
        float sumXDistancesCoordinates = 0;
        float meanXDifferencesCoordinates;
        for (int i = 0; i < img1_xCoordinates.size(); i++) {
            float xDistance = img2_xCoordinates.get(i) - img1_xCoordinates.get(i);
            sumXDistancesCoordinates += xDistance;
        }
        meanXDifferencesCoordinates = sumXDistancesCoordinates/totalNumberOfX;
        return meanXDifferencesCoordinates;
    }

    static Float getMeanYDisplacement(ArrayList<Float> img1_yCoordinates, ArrayList<Float> img2_yCoordinates) {
        int totalNumberOfY = img1_yCoordinates.size();
        float sumYDistancesCoordinates = 0;
        float meanYDifferencesCoordinates;
        for (int i = 0; i < img1_yCoordinates.size(); i++) {
            float yDifference = img2_yCoordinates.get(i) - img1_yCoordinates.get(i);
            sumYDistancesCoordinates += yDifference;
        }
        meanYDifferencesCoordinates = sumYDistancesCoordinates/totalNumberOfY;
        return meanYDifferencesCoordinates;
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
        if (descriptor.type() != CvType.CV_32F) {
            descriptor.convertTo(descriptor, CvType.CV_32F);
        }
        return descriptor;
    }

    public static ArrayList<Double> driftCorrection(Mat img1, Mat img2) {
        ArrayList<Double> driftValues = new ArrayList<>();

        //Load openCv Library, required besides imports
        nu.pattern.OpenCV.loadShared();

        /* 1 - Detect keypoints */
        MatOfKeyPoint keypoints1 = findKeypoints(img1, DETECTORALGO);
        MatOfKeyPoint keypoints2 = findKeypoints(img2, DETECTORALGO);

        /* 2 - Calculate descriptors */
        Mat img1_descriptors = calculDescriptors(img1, keypoints1, DESCRIPTOREXTRACTOR);
        Mat img2_descriptors = calculDescriptors(img2, keypoints2, DESCRIPTOREXTRACTOR);

        /* 3 - Matching descriptor using FLANN matcher */
        MatOfDMatch matcher = matchingDescriptor(img1_descriptors, img2_descriptors, DESCRIPTORMATCHER);
        System.out.println("Number of Matches : " + matcher.rows());

        /* 4 - Select and display Good Matches */
        ArrayList<DMatch> good_matchesList = selectGoodMatches(matcher, keypoints1, keypoints2, UMPERMIN, UMPERPIX, INTERVALINMIN);
        System.out.println("Number of Good Matches : " + good_matchesList.size());

        /* 5 - Get coordinates of GoodMatches Keypoints */
        ArrayList<Float> img1_keypoints_xCoordinates = getGoodMatchesXCoordinates(keypoints1, good_matchesList,true);
        ArrayList<Float> img1_keypoints_yCoordinates = getGoodMatchesYCoordinates(keypoints1, good_matchesList, true);

        ArrayList<Float> img2_keypoints_xCoordinates = getGoodMatchesXCoordinates(keypoints2, good_matchesList,false);
        ArrayList<Float> img2_keypoints_yCoordinates = getGoodMatchesYCoordinates(keypoints2, good_matchesList, false);

        /* 6 - Get X and Y mean displacements */
        float meanXdisplacement = getMeanXDisplacement(img1_keypoints_xCoordinates, img2_keypoints_xCoordinates );
        float meanYdisplacement = getMeanYDisplacement(img1_keypoints_yCoordinates, img2_keypoints_yCoordinates );
        System.out.println("X mean displacement : " + meanXdisplacement);
        System.out.println("Y mean displacement : " + meanYdisplacement + "\n");

        double xVariance = getXVariance(img1_keypoints_xCoordinates, img2_keypoints_xCoordinates, meanXdisplacement);
        double yVariance = getYVariance(img1_keypoints_yCoordinates, img2_keypoints_yCoordinates, meanYdisplacement);
        System.out.println("X variance : " + xVariance);
        System.out.println("Y variance : " + yVariance + "\n");

        driftValues.add((double) meanXdisplacement);
        driftValues.add((double) meanYdisplacement);
        return driftValues;
    }
}

