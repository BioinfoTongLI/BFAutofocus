package edu.univ_tlse3;

import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import mmcorej.*;
import org.json.JSONException;
import org.micromanager.AutofocusPlugin;
import org.micromanager.PositionList;
import org.micromanager.Studio;
import org.micromanager.data.Datastore;
import org.micromanager.data.Image;
import org.micromanager.display.DisplayWindow;
import org.micromanager.internal.utils.*;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.features2d.DescriptorExtractor;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.FeatureDetector;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.SciJavaPlugin;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Plugin(type = AutofocusPlugin.class)
public class BFAutofocus extends AutofocusBase implements AutofocusPlugin, SciJavaPlugin {
    //Some info about the plugin
    private static final String VERSION_INFO = "0.0.1";
    private static final String NAME = "Bright-field autofocus";
    private static final String HELPTEXT = "This simple autofocus is only designed to process transmitted-light (or DIC) images, Z-stack is required.";
    private static final String COPYRIGHT_NOTICE = "CeCILL-B-BSD compatible";

    //Parameters of plugin
    private static final String SEARCH_RANGE = "SearchRange_um";
    private static final String CROP_FACTOR = "CropFactor";
    private static final String CHANNEL = "Channel";
    private static final String EXPOSURE = "Exposure";
    private static final String SHOW_IMAGES = "ShowImages";
    private static final String INCREMENTAL = "incremental";
    private static final String XY_CORRECTION_TEXT = "Correct XY at same time";
    private static final String[] SHOWVALUES = {"Yes", "No"};
    private static final String STEP_SIZE = "Step_size";
    private static final String[] XY_CORRECTION = {"Yes", "No"};
    private static final String[] INCREMENTAL_VALUES = {"Yes", "No"};
    private static final String UMPERSTEP = "µm displacement allowed per time point";
    private static final String PATH_REFIMAGE = "Path of reference image";

    //Set default parameters
    private double searchRange = 5;
    private double cropFactor = 1;
    private String channel = "BF";
    private double exposure = 10;
    private String show = "No";
    private String incremental = "No";
    private int imageCount_;
    private double step = 0.3;
    private String xy_correction = "Yes";
    private Map refImageDict = null;
    private Map oldPositionsDict = null;
    private double umPerStep = 20;
    private String pathOfReferenceImage = "";

    //Constant
    final static double alpha = 0.00390625;

    //Global variables
    private Studio studio_;
    private CMMCore core_;
    private Mat imgRef_Mat = null;

    private double calibration = 0;
    private double intervalInMin =0;
    private int positionIndex = 0;

    //    private PrintStream psError;
//    private PrintStream psOutput;
//    private PrintStream curr_err;
//    private PrintStream curr_out;
    private String savingPath;
    private Datastore storeNonCorrectedImages = null;

    //Begin autofocus
    public BFAutofocus() {
        super.createProperty(SEARCH_RANGE, NumberUtils.doubleToDisplayString(searchRange));
        super.createProperty(CROP_FACTOR, NumberUtils.doubleToDisplayString(cropFactor));
        super.createProperty(EXPOSURE, NumberUtils.doubleToDisplayString(exposure));
        super.createProperty(SHOW_IMAGES, show, SHOWVALUES);
        super.createProperty(INCREMENTAL, incremental, INCREMENTAL_VALUES);
        super.createProperty(XY_CORRECTION_TEXT, xy_correction, XY_CORRECTION);
        super.createProperty(STEP_SIZE, NumberUtils.doubleToDisplayString(step));
        super.createProperty(CHANNEL, channel);
        super.createProperty(UMPERSTEP, NumberUtils.doubleToDisplayString(umPerStep));
//        super.createProperty(PATH_REFIMAGE, pathOfReferenceImage);
        nu.pattern.OpenCV.loadShared();
    }

    @Override
    public void applySettings() {
        try {
            searchRange = NumberUtils.displayStringToDouble(getPropertyValue(SEARCH_RANGE));
            cropFactor = NumberUtils.displayStringToDouble(getPropertyValue(CROP_FACTOR));
            cropFactor = MathFunctions.clip(0.01, cropFactor, 1.0);
            channel = getPropertyValue(CHANNEL);
            exposure = NumberUtils.displayStringToDouble(getPropertyValue(EXPOSURE));
            show = getPropertyValue(SHOW_IMAGES);
            incremental = getPropertyValue(INCREMENTAL);
            xy_correction = getPropertyValue(XY_CORRECTION_TEXT);
            umPerStep = NumberUtils.displayStringToDouble(getPropertyValue(UMPERSTEP));
//            pathOfReferenceImage = getPropertyValue(PATH_REFIMAGE);
        } catch (MMException | ParseException ex) {
            studio_.logs().logError(ex);
        }
    }

    @Override
    public double fullFocus() throws Exception {
        long startTime = new Date().getTime();
        applySettings();
        Rectangle oldROI = studio_.core().getROI();
        core_ = studio_.getCMMCore();

        calibration = core_.getPixelSizeUm();
//        System.out.println("Calibration : " + calibration);
        intervalInMin = (studio_.acquisitions().getAcquisitionSettings().intervalMs)/60000;
        savingPath = studio_.acquisitions().getAcquisitionSettings().root + File.separator;

        //ReportingUtils.logMessage("Original ROI: " + oldROI);
        int w = (int) (oldROI.width * cropFactor);
        int h = (int) (oldROI.height * cropFactor);
        int x = oldROI.x + (oldROI.width - w) / 2;
        int y = oldROI.y + (oldROI.height - h) / 2;
        Rectangle newROI = new Rectangle(x, y, w, h);

        //ReportingUtils.logMessage("Setting ROI to: " + newROI);
        Configuration oldState = null;
        if (channel.length() > 0) {
            String chanGroup = core_.getChannelGroup();
            oldState = core_.getConfigGroupState(chanGroup);
            core_.setConfig(chanGroup, channel);
        }

        //Avoid wasting time on setting roi if it is the same
        if (cropFactor < 1.0) {
            studio_.app().setROI(newROI);
            core_.waitForDevice(core_.getCameraDevice());
        }

        double oldExposure = core_.getExposure();
        core_.setExposure(exposure);

//        //Save logs of outputs and errors
//        try {
//            psError = new PrintStream(savingPath + "Error.LOG");
////            psOutput = new PrintStream(savingPath + "Output.LOG");
//            curr_err = System.err;
////            curr_out = System.out;
////            System.setOut(psOutput);
//        } catch (FileNotFoundException e) {
//            e.printStackTrace();
//        }

        //Get label of position
        PositionList positionList = studio_.positions().getPositionList();
        String label = getLabelOfPositions(positionList);
        System.out.println("Label Position : " + label);

        //Incrementation of position counter; does not work at another place
        positionIndex += 1;

        //Initialization of reference Images and old positions dictionaries
        if (refImageDict == null){
            refImageDict = new HashMap<String, Mat>();
        }

        if (oldPositionsDict == null){
            oldPositionsDict = new HashMap<String, double[]>();
        }

//        String prefix = studio_.acquisitions().getAcquisitionSettings().prefix;
//        System.out.println("Prefix : " + prefix);
//
//        //Initialization of DataStore, to store non corrected images
//        Window displayWindow = (Window) studio_.displays().getCurrentWindow();
//        if (storeNonCorrectedImages == null) {
//            storeNonCorrectedImages = studio_.data().createSinglePlaneTIFFSeriesDatastore(savingPath + prefix + File.separator + "NonCorrectedImages");
//        }
//
//        //Add current non-corrected image to the DataStore
//        core_.snapImage();
//        TaggedImage nonCorrectedTaggedImage = core_.getTaggedImage();
//        Image nonCorrectedImage = studio_.data().convertTaggedImage(nonCorrectedTaggedImage);
//        if (storeNonCorrectedImages.hasImage(nonCorrectedImage.getCoords())) {
//            storeNonCorrectedImages.putImage(nonCorrectedImage);
//        } else {
//            System.out.println("Image did not move");
//        }
//        System.out.println("DataStore getSavePath : " + storeNonCorrectedImages.getSavePath());
//        System.out.println("DataStore Number Of Images : " + storeNonCorrectedImages.getNumImages());

//        boolean saveAcq = studio_.acquisitions().getAcquisitionSettings().save;
//        System.out.println("Save Acquisition images : " + saveAcq);

        double[] oldCorrectedPositions;
        double oldX = core_.getXPosition();
        double oldY = core_.getYPosition();
        double oldZ = getZPosition();

        //Define positions if it does not exist
        if (!oldPositionsDict.containsKey(label)) {
            double[] currentPositions = new double[3];
            currentPositions[0] = core_.getXPosition();
            currentPositions[1] = core_.getYPosition();
            currentPositions[2] = getZPosition();
            oldPositionsDict.put(label, currentPositions);
        } else {
            //Get old calculated X, Y and Z of a given position
            oldCorrectedPositions = getXYZPosition(label);
            oldX = core_.getXPosition();
            oldY = core_.getYPosition();
//            oldX = oldCorrectedPositions[0];
//            oldY = oldCorrectedPositions[1];
            oldZ = oldCorrectedPositions[2];

            //Set to the last good position calculated
            setToLastCorrectedPosition(oldX, oldY, oldZ);
//            System.out.println("old x : " + oldX);
//            System.out.println("old y : " + oldY);
            System.out.println("old z : " + oldZ);
        }

        //Calculate Focus
        double correctedZPosition = calculateZFocus(oldZ);
        System.out.println("Corrected Z Position : " + correctedZPosition);
        //Set to the focus
        setZPosition(correctedZPosition);

        //Get an image to define reference image, for each position
        core_.snapImage();
        TaggedImage taggedImagePosition = core_.getTaggedImage();
        Mat currentMat8Set = convertTo8BitsMat(taggedImagePosition);
//        Image imagePosition = studio_.data().convertTaggedImage(taggedImagePosition);
//        System.out.println("Position Index current TaggedImage : " + taggedImagePosition.tags.getString("PositionIndex"));
//        System.out.println("Frame Index current TaggedImage : " + taggedImagePosition.tags.getString("FrameIndex"));
//        System.out.println("Slice Index current TaggedImage : " + taggedImagePosition.tags.getString("SliceIndex"));
//        System.out.println("Time and Date current TaggedImage : " + imagePosition.tags.getString("Time"));

//        Metadata imagePosition_Metadata = studio_.acquisitions().generateMetadata(imagePosition, true);
//        System.out.println("Metadata : " + imagePosition_Metadata.toString());
//
//        String metadata = imagePosition_Metadata.getReceivedTime();
//        System.out.println("Received Time : " + metadata);

        //Set shutter parameters for acquisition
        boolean oldAutoShutterState = core_.getAutoShutter();
        core_.setAutoShutter(false);
        core_.setShutterOpen(true);


        //Calculation of XY Drifts only if the parameter "Correct XY at same time" is set to Yes;
        double currentXPosition = core_.getXPosition();
        double currentYPosition = core_.getYPosition();

        System.out.println("Current X : " + currentXPosition);
        System.out.println("Current Y : " + currentYPosition);

        double correctedXPosition = currentXPosition;
        double correctedYPosition = currentYPosition;

        System.out.println("Initialization corrected X : " + correctedXPosition);
        System.out.println("Initialization corrected Y : " + correctedYPosition);

        double xCorrection;
        double yCorrection;

        double[] xyDriftsBRISKORB = new double[7];

        double[] xyDriftsORBORB = new double[7];

        double[] xyDriftsORBBRISK = new double[7];

        double[] xyDriftsBRISKBRISK = new double[7];

        double[] xyDriftsAKAZEBRISK = new double[7];

        double[] xyDriftsAKAZEORB = new double[7];

        double[] xyDriftsAKAZEAKAZE = new double[7];

        if (xy_correction.contentEquals("Yes")){
            //Define current image as reference for the position if it does not exist
            if (!refImageDict.containsKey(label)) {
                refImageDict.put(label, currentMat8Set);
            } else {
                //Or calculate XY drift
                imgRef_Mat = (Mat) refImageDict.get(label);
                xyDriftsBRISKORB = calculateXYDrifts(currentMat8Set, FeatureDetector.BRISK, DescriptorExtractor.ORB, DescriptorMatcher.FLANNBASED);

                xyDriftsORBORB = calculateXYDrifts(currentMat8Set, FeatureDetector.ORB, DescriptorExtractor.ORB, DescriptorMatcher.FLANNBASED);

                xyDriftsORBBRISK = calculateXYDrifts(currentMat8Set, FeatureDetector.ORB, DescriptorExtractor.BRISK, DescriptorMatcher.FLANNBASED);

                xyDriftsBRISKBRISK = calculateXYDrifts(currentMat8Set, FeatureDetector.BRISK, DescriptorExtractor.BRISK, DescriptorMatcher.FLANNBASED);

                xyDriftsAKAZEBRISK = calculateXYDrifts(currentMat8Set, FeatureDetector.AKAZE, DescriptorExtractor.BRISK, DescriptorMatcher.FLANNBASED);

                xyDriftsAKAZEORB = calculateXYDrifts(currentMat8Set, FeatureDetector.AKAZE, DescriptorExtractor.ORB, DescriptorMatcher.FLANNBASED);

                xyDriftsAKAZEAKAZE = calculateXYDrifts(currentMat8Set, FeatureDetector.AKAZE, DescriptorExtractor.AKAZE, DescriptorMatcher.FLANNBASED);

                xCorrection = xyDriftsAKAZEBRISK[5];
                yCorrection = xyDriftsAKAZEBRISK[6];
                correctedXPosition = currentXPosition + xCorrection;
                correctedYPosition = currentYPosition + yCorrection;
                System.out.println("Xcorrected : " + correctedXPosition);
                System.out.println("Ycorrected : " + correctedYPosition);
                            }
            setXYPosition(correctedXPosition, correctedYPosition);

            //Reference image incremental
            core_.waitForDevice(core_.getCameraDevice());
            core_.snapImage();
            TaggedImage newRefTaggedImage = core_.getTaggedImage();
            Mat newRefMat = convertTo8BitsMat(newRefTaggedImage);
            refImageDict.replace(label, newRefMat);
        }

        //Reinitialize origin ROI and all other parameters
        core_.setAutoShutter(oldAutoShutterState);

        if (cropFactor < 1.0) {
            studio_.app().setROI(oldROI);
            core_.waitForDevice(core_.getCameraDevice());
        }

        if (oldState != null) {
            core_.setSystemState(oldState);
        }
        core_.setExposure(oldExposure);

//        studio_.app().refreshGUIFromCache(); //Not sure about the utility; may be useful for Metadata?;

        //Refresh positions in position dictionary
        refreshOldXYZposition(correctedXPosition, correctedYPosition, correctedZPosition, label);

//        //Redefine reference image if autofocus is incremental
//        if (Boolean.parseBoolean(incremental)){
//            core_.waitForDevice(core_.getCameraDevice());
//            core_.snapImage();
//            TaggedImage newRefTaggedImage = core_.getTaggedImage();
//            Mat newRefImage_Mat = convertTo8BitsMat(newRefTaggedImage);
//            refImageDict.replace(label, newRefImage_Mat);
//        }

        long endTime = new Date().getTime();
        long acquisitionTimeElapsed = endTime - startTime;
        System.out.println("Acquisition duration in ms : " + acquisitionTimeElapsed);

        writeOutput(acquisitionTimeElapsed, label, oldX, oldY, oldZ,
                currentXPosition, correctedXPosition, currentYPosition, correctedYPosition, correctedZPosition,
                xyDriftsBRISKORB, xyDriftsORBORB, xyDriftsORBBRISK, xyDriftsBRISKBRISK,
                xyDriftsAKAZEBRISK, xyDriftsAKAZEORB, xyDriftsAKAZEAKAZE);
//        psError.close();
//        psOutput.close();
//        System.setOut(curr_out);
//        System.setErr(curr_err);

//        //Save Datastore
//        storeNonCorrectedImages.save(displayWindow);
        return correctedZPosition;
    }

    private double getZPosition() throws Exception {
        String focusDevice = core_.getFocusDevice();
        double z = core_.getPosition(focusDevice);
        return z;
    }
    private double[] getXYZPosition(String label) {
        return (double[]) oldPositionsDict.get(label);
    }

    private String getLabelOfPositions(PositionList positionList) {
        if (positionIndex == positionList.getNumberOfPositions() ) {
            positionIndex = 0;
        }
        return positionList.getPosition(positionIndex).getLabel();
    }

    private void refreshOldXYZposition(double correctedXPosition, double correctedYPosition, double correctedZPosition, String label) {
        double[] refreshedXYZposition = new double[3];
        refreshedXYZposition[0] = correctedXPosition;
        refreshedXYZposition[1] = correctedYPosition;
        refreshedXYZposition[2] = correctedZPosition;
        oldPositionsDict.replace(label, refreshedXYZposition);
    }

    private void setToLastCorrectedPosition(double oldX, double oldY, double oldZ) throws Exception {
//        if (xyzPosition.length == 0) {
//            setXYPosition(core_.getXPosition(), core_.getYPosition());
//            String focusDevice = core_.getFocusDevice();
//            double currentZ = core_.getPosition(focusDevice);
//            setZPosition(currentZ);
//        } else {
        setXYPosition(oldX, oldY);
        setZPosition(oldZ);
//        }
    }

    private double calculateZFocus(double oldZ) throws Exception {
        double[] zpositions = calculateZPositions(searchRange, step, oldZ);
        double[] stdAtZPositions = new double[zpositions.length];
        TaggedImage currentImg;

        boolean oldAutoShutterState = core_.getAutoShutter();
        core_.setAutoShutter(false);
        core_.setShutterOpen(true);

        for (int i =0; i< zpositions.length ;i++){
            setZPosition(zpositions[i]);
            core_.waitForDevice(core_.getCameraDevice());
            core_.snapImage();
            currentImg = core_.getTaggedImage();
            imageCount_++;
            Image img = studio_.data().convertTaggedImage(currentImg);
            stdAtZPositions[i] = studio_.data().ij().createProcessor(img).getStatistics().stdDev;
            if (show.contentEquals("Yes")) {
                showImage(currentImg);
            }
        }

        core_.setAutoShutter(oldAutoShutterState);
        int rawIndex = getZfocus(stdAtZPositions);
        return optimizeZFocus(rawIndex, stdAtZPositions, zpositions);
    }

    public static int getZfocus (double[] stdArray){
        double min = Double.MAX_VALUE;
        int maxIdx = Integer.MAX_VALUE;
        for (int i = 0; i < stdArray.length; i++){
            if (stdArray[i] < min){
                maxIdx = i;
                min = stdArray[i];
            }
        }
        return maxIdx;
    }

    private double[] calculateXYDrifts(Mat currentImgMat, Integer detectorAlgo, Integer descriptorExtractor, Integer descriptorMatcher) throws Exception {
        //uncomment next line before simulation:
        //currentImgMat = DriftCorrection.readImage("/home/dataNolwenn/Résultats/06-03-2018/ImagesFocus/19-5.tif");

        ExecutorService es = Executors.newSingleThreadExecutor();
        Future job = es.submit(new ThreadAttribution(imgRef_Mat, currentImgMat, calibration,
                intervalInMin, umPerStep, detectorAlgo, descriptorExtractor, descriptorMatcher));
        double[] xyDrifts = (double[]) job.get();
        es.shutdown();
        try {
            es.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return xyDrifts;
//        return DriftCorrection.driftCorrection(imgRef_Mat, currentImgMat);
    }

    private void showImage(TaggedImage currentImg) {
        if (show.contentEquals("Yes")) {
            SwingUtilities.invokeLater(() -> {
                try {
                    studio_.live().displayImage(studio_.data().convertTaggedImage(currentImg));
                }
                catch (JSONException | IllegalArgumentException e) {
                    studio_.logs().showError(e);
                }
            });
        }
    }

    private void setXYPosition(double x, double y) throws Exception {
//        CMMCore core_ = studio_.getCMMCore();
        String xyDevice = core_.getXYStageDevice();
        core_.setXYPosition(x,y);
        core_.waitForDevice(xyDevice);
    }

    private void setZPosition(double z) throws Exception {
//        CMMCore core_ = studio_.getCMMCore();
        String focusDevice = core_.getFocusDevice();
        core_.setPosition(focusDevice, z);
        core_.waitForDevice(focusDevice);
    }

    public static double[] calculateZPositions(double searchRange, double step, double startZUm){
        double lower = startZUm - searchRange/2;
        int nstep  = new Double(searchRange/step).intValue() + 1;
        double[] zpos = new double[nstep];
        for (int p = 0; p < nstep; p++){
            zpos[p] = lower + p * step;
        }
        return zpos;
    }

    public static int getMinZfocus (double[] stdArray){
        double min = Double.MAX_VALUE;
        int maxIdx = Integer.MAX_VALUE;
        for (int i = 0; i < stdArray.length; i++){
            if (stdArray[i] < min){
                maxIdx = i;
                min = stdArray[i];
            }
        }
        return maxIdx;
    }

    public static double optimizeZFocus(int rawZidx, double[] stdArray, double[] zpositionArray){
        if (rawZidx == zpositionArray.length-1 || rawZidx == 0){
            return zpositionArray[rawZidx];
        }
        int oneLower = rawZidx-1;
        int oneHigher = rawZidx+1;
        double lowerVarDiff = stdArray[oneLower] - stdArray[rawZidx];
        double upperVarDiff = stdArray[rawZidx] - stdArray[oneHigher];
        if (lowerVarDiff * lowerVarDiff < upperVarDiff * upperVarDiff){
            return (zpositionArray[oneLower] + zpositionArray[rawZidx]) / 2;
        }else if(lowerVarDiff * lowerVarDiff > upperVarDiff * upperVarDiff){
            return (zpositionArray[rawZidx] + zpositionArray[oneHigher]) / 2;
        }else{
            return zpositionArray[rawZidx];
        }
    }

    private static double[] calculateDistances(List<double[]> xysList) {
        double[] distances = new double[xysList.size()];
        for (int i = 0; i < xysList.size(); i++) {
            double[] currentXY = xysList.get(i);
            distances[i] = Math.hypot(currentXY[0], currentXY[1]);
        }
        return distances;
    }

    private static int getIndexOfBestDistance(double[] distances) {
        double min = Double.MAX_VALUE;
        int minIndex = 0;
        for (int i = 0; i < distances.length; i++) {
            if (distances[i] < min) {
                min = distances[i];
                minIndex = i;
            }
        }
        return minIndex;
    }

    private static int getIndexOfMaxNumberGoodMatches(double[] numberOfMatches) {
        double max = Double.MIN_VALUE;
        int maxIndex = 0;
        for (int i = 0; i < numberOfMatches.length; i++) {
            if (numberOfMatches[i] > max) {
                max = numberOfMatches[i];
                maxIndex = i;
            }
        }
        return maxIndex;
    }

    public static int getindexOfBestFocus(double[] stdAtZPositions, double[] zpositions, double[] numberOfMatches, List<double[]> drifts) {
        //Calcul of focus by number of Good Matches
        int indexOfMaxNumberGoodMatches = getIndexOfMaxNumberGoodMatches(numberOfMatches);
//        double zMaxNumberGoodMatches = zpositions[indexOfMaxNumberGoodMatches];

        //Calcul of focus by StdDev
        int indexFocusStdDev = getMinZfocus(stdAtZPositions);
//        double zOptimizedStdDev = optimizeZFocus(indexFocusStdDev, stdAtZPositions, zpositions);

        //Get the mean of the 2 focus found
        return (indexOfMaxNumberGoodMatches + indexFocusStdDev) / 2;
    }

    private double[] getVariances(List<double[]> xysList) {
        double[] xDistances = new double[xysList.size()];
        double[] yDistances = new double[xysList.size()];
        double xSum = 0;
        double ySum = 0;
        double xNumber = 0;
        double yNumber = 0;
        double xMean;
        double yMean;

        for (int i = 0; i < xysList.size(); i++) {
            double[] currentXY = xysList.get(i);
            xDistances[i] = currentXY[0];
            yDistances[i] = currentXY[1];

            xSum += xDistances[i];
            ySum += yDistances[i];

            xNumber += 1;
            yNumber += 1;
        }

        xMean = xSum / xNumber;
        yMean = ySum / yNumber;

        double xDiff = 0;
        double yDiff = 0;
        double xVariance;
        double yVariance;

        for (int i = 0; i < xDistances.length; i++) {
            xDiff += Math.pow(xDistances[i] - xMean, 2);
            yDiff += Math.pow(yDistances[i] - yMean, 2);
        }

        xVariance = xDiff / xNumber;
        yVariance = yDiff / yNumber;

        double[] listVar = new double[2];

        listVar[0] = xVariance;
        listVar[1] = yVariance;
        System.out.println("\nVariance de X : " + xVariance);
        System.out.println("Variance de Y : " + yVariance);

        return listVar;
    }

    //Convert MM TaggedImage to OpenCV Mat
    private static Mat convertToMat(TaggedImage img) throws JSONException {
        int width = img.tags.getInt("Width");
        int height = img.tags.getInt("Height");
        Mat mat = new Mat(height, width, CvType.CV_16UC1);
        mat.put(0,0, (short[]) img.pix);
        return mat;
    }

    //Convert MM TaggedImage to OpenCV 8 bits Mat
    private static Mat convertTo8BitsMat(TaggedImage taggedImage) throws JSONException {
        Mat mat16 = convertToMat(taggedImage);
        Mat mat8 = new Mat(mat16.cols(), mat16.rows(), CvType.CV_8UC1);
        mat16.convertTo(mat8, CvType.CV_8UC1);//, alpha);
        return DriftCorrection.equalizeImages(mat8);
    }

    //Convert MM Short Processor to OpenCV Mat
    private static Mat toMat(ShortProcessor sp) {
        final int w = sp.getWidth();
        final int h = sp.getHeight();
        Mat mat = new Mat(h, w, CvType.CV_16UC1);
        mat.put(0,0, (short[]) sp.getPixels());
        Mat res = new Mat(h, w, CvType.CV_8UC1);
        mat.convertTo(res, CvType.CV_8UC1, alpha);
        return DriftCorrection.equalizeImages(res);
    }

    private void writeOutput(long acquisitionDuration, String label, double oldX,
                             double oldY, double oldZ, double currentXPosition, double correctedXPosition, double currentYPosition,
                             double correctedYPosition, double correctedZPosition, double[] xyDriftsBRISKORB,
                             double[] xyDriftsORBORB, double[] xyDriftsORBBRISK, double[] xyDriftsBRISKBRISK,
                             double[] xyDriftsAKAZEAKAZE, double[] xyDriftsAKAZEBRISK, double[] xyDriftsAKAZEORB) throws IOException {

        String prefix = studio_.acquisitions().getAcquisitionSettings().prefix;
        //For "statistics"
        File f1 = new File(savingPath + prefix + "_Stats_" + label + ".csv");
        if (!f1.exists()) {
            f1.createNewFile();
            FileWriter fw = new FileWriter(f1);
            String[] headersOfFile = new String[]{"labelOfPosition", "oldX", "oldY", "oldZ",
                    "currentXPosition", "correctedXPosition", "currentYPosition", "correctedYPosition",
                    "correctedZPosition", "acquisitionDuration(ms)",
                    "meanXdisplacementBRISKORB", "meanYdisplacementBRISKORB", "meanXdisplacementORBORB", "meanYdisplacementORBORB",
                    "meanXdisplacementORBBRISK", "meanYdisplacementORBBRISK", "meanXdisplacementBRISKBRISK", "meanYdisplacementBRISKBRISK",
                    "meanXdisplacementAKAZEBRISK", "meanYdisplacementAKAZEBRISK", "meanXdisplacementAKAZEORB", "meanYdisplacementAKAZEORB",
                    "meanXdisplacementAKAZEAKAZE", "meanYdisplacementAKAZEAKAZE",

                    "numberOfMatchesBRISKORB", "numberOfMatchesORBORB", "numberOfMatchesORBBRISK", "numberOfMatchesBRISKBRISK",
                    "numberOfMatchesAKAZEBRISK", "numberOfMatchesAKAZEORB", "numberOfMatchesAKAZEAKAZE",

                    "numberOfGoodMatchesBRISKORB", "numberOfGoodMatchesORBORB", "numberOfGoodMatchesORBBRISK",
                    "numberOfGoodMatchesBRISKBRISK", "numberOfGoodMatchesAKAZEBRISK", "numberOfGoodMatchesAKAZEORB",
                    "numberOfGoodMatchesAKAZEAKAZE",

                    "algorithmDurationBRISKORB(ms)", "algorithmDurationORBORB(ms)", "algorithmDurationORBBRISK(ms)",
                    "algorithmDurationBRISKBRISK(ms)", "algorithmDurationAKAZEBRISK(ms)", "algorithmDurationAKAZEORB(ms)",
                    "algorithmDurationAKAZEAKAZE(ms)",

                    "medianXDisplacementBRISKORB", "medianXDisplacementORBORB", "medianXDisplacementORBBRISK",
                    "medianXDisplacementBRISKBRISK", "medianXDisplacementAKAZEBRISK", "medianXDisplacementAKAZEORB",
                    "medianXDisplacementAKAZEAKAZE",

                    "medianYDisplacementBRISKORB", "medianYDisplacementORBORB", "medianYDisplacementORBBRISK",
                    "medianYDisplacementBRISKBRISK", "medianYDisplacementAKAZEBRISK", "medianYDisplacementAKAZEORB",
                    "medianYDisplacementAKAZEAKAZE"
            } ;

            fw.write(String.join(",", headersOfFile) + System.lineSeparator());
//            fw.write("labelOfPosition" + "," + "xCorrection" + "," + "yCorrection" + "," + "oldX" + "," + "oldY" + "," + "oldZ" + ","
//                    + "correctedXPosition" + "," + "correctedYPosition" + "," + "correctedZPosition" + "," + "timeElapsed" + ","
//                    + "meanXdisplacementBRISK" + "," + "meanYdisplacementBRISK" + "," + "numberOfMatchesBRISK" + "," + "numberOfGoodMatchesBRISK" + ","
//                    + "meanXdisplacementORB" + "," + "meanYdisplacementORB" + "," + "numberOfMatchesORB" + "," + "numberOfGoodMatchesORB" + System.lineSeparator());
            fw.close();
        } else {
            double meanXdisplacementBRISKORB = xyDriftsBRISKORB[0];
            double meanYdisplacementBRISKORB = xyDriftsBRISKORB[1];
            double numberOfMatchesBRISKORB = xyDriftsBRISKORB[2];
            double numberOfGoodMatchesBRISKORB = xyDriftsBRISKORB[3];
            double algorithmDurationBRISKORB = xyDriftsBRISKORB[4];
            double medianXDisplacementBRISKORB = xyDriftsBRISKORB[5];
            double medianYDisplacementBRISKORB = xyDriftsBRISKORB[6];


            double meanXdisplacementORBORB = xyDriftsORBORB[0];
            double meanYdisplacementORBORB = xyDriftsORBORB[1];
            double numberOfMatchesORBORB = xyDriftsORBORB[2];
            double numberOfGoodMatchesORBORB = xyDriftsORBORB[3];
            double algorithmDurationORBORB = xyDriftsORBORB[4];
            double medianXDisplacementORBORB = xyDriftsORBORB[5];
            double medianYDisplacementORBORB = xyDriftsORBORB[6];

            double meanXdisplacementORBBRISK = xyDriftsORBBRISK[0];
            double meanYdisplacementORBBRISK = xyDriftsORBBRISK[1];
            double numberOfMatchesORBBRISK = xyDriftsORBBRISK[2];
            double numberOfGoodMatchesORBBRISK = xyDriftsORBBRISK[3];
            double algorithmDurationORBBRISK = xyDriftsORBBRISK[4];
            double medianXDisplacementORBBRISK = xyDriftsORBBRISK[5];
            double medianYDisplacementORBBRISK = xyDriftsORBBRISK[6];

            double meanXdisplacementBRISKBRISK = xyDriftsBRISKBRISK[0];
            double meanYdisplacementBRISKBRISK = xyDriftsBRISKBRISK[1];
            double numberOfMatchesBRISKBRISK = xyDriftsBRISKBRISK[2];
            double numberOfGoodMatchesBRISKBRISK = xyDriftsBRISKBRISK[3];
            double algorithmDurationBRISKBRISK = xyDriftsBRISKBRISK[4];
            double medianXDisplacementBRISKBRISK = xyDriftsBRISKBRISK[5];
            double medianYDisplacementBRISKBRISK = xyDriftsBRISKBRISK[6];

            double meanXdisplacementAKAZEBRISK = xyDriftsAKAZEBRISK[0];
            double meanYdisplacementAKAZEBRISK = xyDriftsAKAZEBRISK[1];
            double numberOfMatchesAKAZEBRISK = xyDriftsAKAZEBRISK[2];
            double numberOfGoodMatchesAKAZEBRISK = xyDriftsAKAZEBRISK[3];
            double algorithmDurationAKAZEBRISK = xyDriftsAKAZEBRISK[4];
            double medianXDisplacementAKAZEBRISK = xyDriftsAKAZEBRISK[5];
            double medianYDisplacementAKAZEBRISK = xyDriftsAKAZEBRISK[6];

            double meanXdisplacementAKAZEORB = xyDriftsAKAZEORB[0];
            double meanYdisplacementAKAZEORB = xyDriftsAKAZEORB[1];
            double numberOfMatchesAKAZEORB = xyDriftsAKAZEORB[2];
            double numberOfGoodMatchesAKAZEORB = xyDriftsAKAZEORB[3];
            double algorithmDurationAKAZEORB = xyDriftsAKAZEORB[4];
            double medianXDisplacementAKAZEORB = xyDriftsAKAZEORB[5];
            double medianYDisplacementAKAZEORB = xyDriftsAKAZEORB[6];

            double meanXdisplacementAKAZEAKAZE = xyDriftsAKAZEAKAZE[0];
            double meanYdisplacementAKAZEAKAZE = xyDriftsAKAZEAKAZE[1];
            double numberOfMatchesAKAZEAKAZE = xyDriftsAKAZEAKAZE[2];
            double numberOfGoodMatchesAKAZEAKAZE = xyDriftsAKAZEAKAZE[3];
            double algorithmDurationAKAZEAKAZE = xyDriftsAKAZEAKAZE[4];
            double medianXDisplacementAKAZEAKAZE = xyDriftsAKAZEAKAZE[5];
            double medianYDisplacementAKAZEAKAZE = xyDriftsAKAZEAKAZE[6];

            FileWriter fw1 = new FileWriter(f1, true);
            fw1.write(label + "," + oldX + "," + oldY + "," + oldZ + ","
                    + currentXPosition + "," + correctedXPosition + "," + currentYPosition + "," + correctedYPosition + ","
                    + correctedZPosition + "," + acquisitionDuration + ","

                    + meanXdisplacementBRISKORB + "," + meanYdisplacementBRISKORB + "," + meanXdisplacementORBORB + "," + meanYdisplacementORBORB + ","
                    + meanXdisplacementORBBRISK + "," + meanYdisplacementORBBRISK + "," + meanXdisplacementBRISKBRISK + "," + meanYdisplacementBRISKBRISK + ","
                    + meanXdisplacementAKAZEBRISK + "," + meanYdisplacementAKAZEBRISK + "," + meanXdisplacementAKAZEORB + "," + meanYdisplacementAKAZEORB + ","
                    + meanXdisplacementAKAZEAKAZE + "," + meanYdisplacementAKAZEAKAZE + ","

                    + numberOfMatchesBRISKORB + "," + numberOfMatchesORBORB + "," + numberOfMatchesORBBRISK + "," + numberOfMatchesBRISKBRISK + ","
                    + numberOfMatchesAKAZEBRISK + "," + numberOfMatchesAKAZEORB + "," + numberOfMatchesAKAZEAKAZE + ","

                    + numberOfGoodMatchesBRISKORB + "," + numberOfGoodMatchesORBORB + "," + numberOfGoodMatchesORBBRISK + ","
                    + numberOfGoodMatchesBRISKBRISK + "," + numberOfGoodMatchesAKAZEBRISK + "," + numberOfGoodMatchesAKAZEORB + ","
                    + numberOfGoodMatchesAKAZEAKAZE + ","

                    + algorithmDurationBRISKORB + "," + algorithmDurationORBORB + "," + algorithmDurationORBBRISK + ","
                    + algorithmDurationBRISKBRISK + "," + algorithmDurationAKAZEBRISK + "," + algorithmDurationAKAZEORB + ","
                    + algorithmDurationAKAZEAKAZE + ","

                    + medianXDisplacementBRISKORB + "," + medianYDisplacementBRISKORB + "," + medianXDisplacementORBORB + "," + medianYDisplacementORBORB + ","
                    + medianXDisplacementORBBRISK + "," + medianYDisplacementORBBRISK + "," + medianXDisplacementBRISKBRISK + "," + medianYDisplacementBRISKBRISK + ","
                    + medianXDisplacementAKAZEBRISK + "," + medianYDisplacementAKAZEBRISK + "," + medianXDisplacementAKAZEORB + "," + medianYDisplacementAKAZEORB + ","
                    + medianXDisplacementAKAZEAKAZE + "," + medianYDisplacementAKAZEAKAZE + ","

                    + System.lineSeparator());
            fw1.close();
        }
    }

    @Override
    public double incrementalFocus() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int getNumberOfImages() {
        return imageCount_;
    }

    @Override
    public String getVerboseStatus() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public double getCurrentFocusScore() {
        throw new UnsupportedOperationException("Not supported yet. You have to take z-stack.");
    }

    @Override
    public double computeScore(ImageProcessor imageProcessor) {
        return imageProcessor.getStatistics().stdDev;
    }

    @Override
    public void setContext(Studio studio) {
        studio_ = studio;
        studio_.events().registerForEvents(this);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getHelpText() {
        return HELPTEXT;
    }

    @Override
    public String getVersion() {
        return VERSION_INFO;
    }

    @Override
    public String getCopyright() {
        return COPYRIGHT_NOTICE;
    }

    @Override
    public PropertyItem[] getProperties() {
        CMMCore core = studio_.getCMMCore();
        String channelGroup = core.getChannelGroup();
        StrVector channels = core.getAvailableConfigs(channelGroup);
        String allowedChannels[] = new String[(int)channels.size() + 1];
        allowedChannels[0] = "";

        try {
            PropertyItem p = getProperty(CHANNEL);
            boolean found = false;
            for (int i = 0; i < channels.size(); i++) {
                allowedChannels[i+1] = channels.get(i);
                if (p.value.equals(channels.get(i))) {
                    found = true;
                }
            }
            p.allowed = allowedChannels;
            if (!found) {
                p.value = allowedChannels[0];
            }
            setProperty(p);
        } catch (Exception e) {
            ReportingUtils.logError(e);
        }

        return super.getProperties();
    }

    //*************************** Class for multithreading ***************************//
    private class ThreadAttribution implements Callable<double[]> {

        private Mat img1_;
        private Mat img2_;
        private double calibration_;
        private double intervalInMs_;
        private double umPerStep_;
        private Integer detectorAlgo_;
        private Integer descriptorExtractor_;
        private Integer descriptorMatcher_;

        ThreadAttribution(Mat img1, Mat img2, double calibration, double intervalInMs, double umPerStep,
                          Integer detectorAlgo, Integer descriptorExtractor, Integer descriptorMatcher) {
            img1_ = img1;
            img2_ = img2;
            calibration_ = calibration;
            intervalInMs_ = intervalInMs;
            umPerStep_ = umPerStep;
            detectorAlgo_ = detectorAlgo;
            descriptorExtractor_ = descriptorExtractor;
            descriptorMatcher_ = descriptorMatcher;
        }

        @Override
        public double[] call() {
            return DriftCorrection.driftCorrection(img1_, img2_, calibration_, intervalInMs_, umPerStep_, detectorAlgo_, descriptorExtractor_, descriptorMatcher_);
        }
    }


}

