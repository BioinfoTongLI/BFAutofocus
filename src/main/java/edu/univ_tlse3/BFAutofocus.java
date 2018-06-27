package edu.univ_tlse3;

import ij.IJ;
import ij.gui.YesNoCancelDialog;
import ij.process.ImageProcessor;
import mmcorej.*;
import org.json.JSONException;
import org.micromanager.AutofocusPlugin;
import org.micromanager.PositionList;
import org.micromanager.Studio;
import org.micromanager.data.*;
import org.micromanager.data.Image;
import org.micromanager.data.Metadata;
import org.micromanager.data.internal.DefaultCoords;
import org.micromanager.data.internal.DefaultMetadata;
import org.micromanager.data.internal.DefaultSummaryMetadata;
import org.micromanager.internal.utils.*;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.features2d.DescriptorExtractor;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.FeatureDetector;
import org.opencv.imgcodecs.Imgcodecs;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.SciJavaPlugin;

import java.awt.*;
import java.io.*;
import java.text.ParseException;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

@Plugin(type = AutofocusPlugin.class)
public class BFAutofocus extends AutofocusBase implements AutofocusPlugin, SciJavaPlugin {
    //Some info about the plugin
    public static final String VERSION_INFO = "0.0.1";
    public static final String NAME = "Bright-field autofocus";
    public static final String HELPTEXT = "This simple autofocus is only designed to process transmitted-light (or DIC) images, Z-stack is required.";
    public static final String COPYRIGHT_NOTICE = "CeCILL-B-BSD compatible";

    //Parameters of plugin
    public static final String SEARCH_RANGE = "SearchRange_um";
    public static final String CROP_FACTOR = "CropFactor";
    public static final String CHANNEL = "Channel";
    public static final String EXPOSURE = "Exposure";
    public static final String DETECTORALGO_TEXT = "Feature detector algorithm";
    public static final String[] DETECTORALGO_VALUES = {"AKAZE", "BRISK", "ORB"};
    public static final String MATCHERALGO_TEXT = "Matches extractor algorithm";
    public static final String[] MATCHERALGO_VALUES = {"AKAZE", "BRISK", "ORB"};
    public static final String SHOWIMAGES_TEXT = "ShowImages";
    public static final String[] SHOWIMAGES_VALUES = {"Yes", "No"};
    public static final String SAVEIMGS_TEXT = "SaveImages";
    public static final String[] SAVEIMAGES_VALUES = {"Yes", "No"};
    public static final String STEP_SIZE = "Step_size";
    public static final String XY_CORRECTION_TEXT = "Correct XY at same time";
    public static final String[] XY_CORRECTION_VALUES = {"Yes", "No"};
    public static final String UMPERSTEP = "µm displacement allowed per time point";
    public static final String Z_OFFSET = "Z offset";
    public static final String TESTALLALGOS_TEXT = "Test all possible algorithms";
    public static final String[] TESTALLALGOS_VALUES = {"Yes", "No"};
    public static final String DRIFTCALCUL_TEXT = "Method to calculate drift";
    public static final String[] DRIFTCALCUL_VALUES = {"Mean", "Harmonic Mean", "Median", "Minimum Distance"};

    //Set default parameters
    public double searchRange = 5;
    public double cropFactor = 1;
    public String channel = "BF";
    public double exposure = 50;
    public String show = "No";
    public String save = "Yes";
    public int imageCount = 0;
    public int timepoint = 0;
    public double step = 0.3;
    public String xy_correction = "Yes";
    public Map<String, Mat> refImageDict = new HashMap<>();
    public Map<String, double[]> oldPositionsDict = new HashMap<>();
    public double umPerStep = 10;
    public String testAllAlgos = "No";
    public String detectorAlgo = "AKAZE";
    public String matcherAlgo = "BRISK";
    public double zOffset = -1;
    public String driftCalcul = "Median";

    //Global variables
    public Studio studio_;
    public CMMCore core_;
    public Mat imgRef_Mat = null;
    public double calibration = 0;
    public double intervalInMin = 0;
    public int positionIndex = 0;
    public String savingPath;
    public Datastore store;
    public final String algoToUseMultiple = "AKAZEBRISK";

    //Begin autofocus
    public BFAutofocus() {
        super.createProperty(CROP_FACTOR, NumberUtils.doubleToDisplayString(cropFactor));
        super.createProperty(EXPOSURE, NumberUtils.doubleToDisplayString(exposure));
        super.createProperty(SEARCH_RANGE, NumberUtils.doubleToDisplayString(searchRange));
        super.createProperty(STEP_SIZE, NumberUtils.doubleToDisplayString(step));
        super.createProperty(Z_OFFSET, NumberUtils.doubleToDisplayString(zOffset));
        super.createProperty(SHOWIMAGES_TEXT, show, SHOWIMAGES_VALUES);
        super.createProperty(XY_CORRECTION_TEXT, xy_correction, XY_CORRECTION_VALUES);
        super.createProperty(TESTALLALGOS_TEXT, testAllAlgos, TESTALLALGOS_VALUES);
        super.createProperty(DETECTORALGO_TEXT, detectorAlgo, DETECTORALGO_VALUES);
        super.createProperty(MATCHERALGO_TEXT, matcherAlgo, MATCHERALGO_VALUES);
        super.createProperty(DRIFTCALCUL_TEXT, driftCalcul, DRIFTCALCUL_VALUES);
        super.createProperty(CHANNEL, channel);
        super.createProperty(UMPERSTEP, NumberUtils.doubleToDisplayString(umPerStep));
        super.createProperty(SAVEIMGS_TEXT, save, SAVEIMAGES_VALUES);
        nu.pattern.OpenCV.loadShared();
    }

    @Override
    public void applySettings() {
        try {
            cropFactor = NumberUtils.displayStringToDouble(getPropertyValue(CROP_FACTOR));
            cropFactor = MathFunctions.clip(0.01, cropFactor, 1.0);
            exposure = NumberUtils.displayStringToDouble(getPropertyValue(EXPOSURE));
            searchRange = NumberUtils.displayStringToDouble(getPropertyValue(SEARCH_RANGE));
            step = NumberUtils.displayStringToDouble(getPropertyValue(STEP_SIZE));
            zOffset = NumberUtils.displayStringToDouble(getPropertyValue(Z_OFFSET));
            show = getPropertyValue(SHOWIMAGES_TEXT);
            xy_correction = getPropertyValue(XY_CORRECTION_TEXT);
            testAllAlgos = getPropertyValue(TESTALLALGOS_TEXT);
            detectorAlgo = getPropertyValue(DETECTORALGO_TEXT);
            matcherAlgo = getPropertyValue(MATCHERALGO_TEXT);
//            if ((detectorAlgo.equals("ORB") || detectorAlgo.equals("BRISK")) && matcherAlgo.equals("AKAZE")) {
//                ReportingUtils.showMessage("This combination does not work. Please choose another one");
//            }
//            if (detectorAlgo.equals("ORB") && matcherAlgo.equals("BRISK")) {
//                YesNoCancelDialog yesNoCancelDialog = new YesNoCancelDialog(null, "Warning message :",
//                        "No result can be guaranteed by using these two algorithms. Do you want to proceed anyway?");
//            }

            driftCalcul = getPropertyValue(DRIFTCALCUL_TEXT);
//            switch (driftCalcul) {
//                case "Mean" :
//                    YesNoCancelDialog yesNoCancelMean = new YesNoCancelDialog(null, "Information message : ",
//                            "It is the fastest drift calculation method but the less accurate. Do you want to proceed?");
//                    break;
//                case "Harmonic Mean" :
//                    YesNoCancelDialog yesNoCancelHarmonicMean = new YesNoCancelDialog(null, "Information message : ",
//                            "It is the second fastest drift calculation method but the second less accurate. Do you want to proceed?");
//                    break;
//                case "Median" :
//                    YesNoCancelDialog yesNoCancelMedian = new YesNoCancelDialog(null, "Information message : ",
//                            "It is the most accurate drift calculation method but the least fast. Do you want to proceed?");
//                    break;
//                case "Minimum Distance" :
//                    YesNoCancelDialog yesNoCancelMinimumDistance = new YesNoCancelDialog(null, "Information message : ",
//                            "It is the most accurate drift calculation method but the least fast. Do you want to proceed?");
//                    break;
//            }

            channel = getPropertyValue(CHANNEL);
            umPerStep = NumberUtils.displayStringToDouble(getPropertyValue(UMPERSTEP));
            save = getPropertyValue(SAVEIMGS_TEXT);

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
        intervalInMin = (studio_.acquisitions().getAcquisitionSettings().intervalMs)/60000;
        savingPath = studio_.acquisitions().getAcquisitionSettings().root + File.separator;
        String prefix = studio_.acquisitions().getAcquisitionSettings().prefix;

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

        boolean oldAutoShutterState = core_.getAutoShutter();
        core_.setAutoShutter(false);
        core_.setShutterOpen(true);

        //Get label of position
        PositionList positionList = studio_.positions().getPositionList();
        String label;

        if (positionList.getNumberOfPositions() == 0){
            if (positionIndex > 0 ) {
                positionIndex = 0;
            }
            label = positionList.generateLabel();
        }else{
            label = getLabelOfPositions(positionList);
        }

        ReportingUtils.logMessage("Position : " + label + " at time point : " + timepoint);

        //Creation of BF saving directory
        String bfPath = savingPath + "BFs";
        if (save.contentEquals("Yes") && !new File(bfPath).exists()){
            store = studio_.data().createMultipageTIFFDatastore(
                    bfPath, false,true);
            if (show.contentEquals("Yes")) {
                studio_.displays().createDisplay(store);
            }
        }

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
            oldX = oldCorrectedPositions[0];
            oldY = oldCorrectedPositions[1];
            oldZ = oldCorrectedPositions[2];

            //Set to the last good position calculated
            setToLastCorrectedPosition(oldX, oldY, oldZ);
        }

        //Calculate Focus
        double correctedZPosition = calculateZFocus(oldZ, save.contentEquals("Yes"));
        ReportingUtils.logMessage("Corrected Z Position : " + correctedZPosition);

        //Set to the focus
        setZPosition(correctedZPosition + zOffset);

        //Get an image to define reference image, for each position
        core_.waitForDevice(core_.getCameraDevice());
        core_.snapImage();
        Mat currentMat8Set = convertTo8BitsMat(core_.getTaggedImage());
        Imgcodecs.imwrite(savingPath + prefix + "_" + label + "_T" + timepoint + "_Ref.tif", currentMat8Set);

        //Calculation of XY Drifts only if the parameter "Correct XY at same time" is set to Yes;
        double currentXPosition = core_.getXPosition();
        double currentYPosition = core_.getYPosition();

        double correctedXPosition = currentXPosition;
        double correctedYPosition = currentYPosition;

        double xCorrection = 0;
        double yCorrection = 0;
        double threshold = 0;

        if (xy_correction.contentEquals("Yes")){
            //Test All Possible Algorithms or not:
            if (testAllAlgos.contentEquals("Yes")) {
                double[] xyDriftsBRISKORB = new double[11];
                double[] xyDriftsORBORB = new double[11];
                double[] xyDriftsORBBRISK = new double[11];
                double[] xyDriftsBRISKBRISK = new double[11];
                double[] xyDriftsAKAZEBRISK = new double[11];
                double[] xyDriftsAKAZEORB = new double[11];
                double[] xyDriftsAKAZEAKAZE = new double[11];

                if (!refImageDict.containsKey(label)) {
                    refImageDict.put(label, currentMat8Set);
                } else {
                    //Or calculate XY drift
                    imgRef_Mat = refImageDict.get(label);
                    List<double[]> listOfDrifts = calculateMultipleXYDrifts(currentMat8Set, FeatureDetector.BRISK, FeatureDetector.ORB,
                            FeatureDetector.AKAZE, DescriptorExtractor.BRISK, DescriptorExtractor.ORB,
                            DescriptorExtractor.AKAZE, DescriptorMatcher.FLANNBASED,
                            oldROI, oldState, oldExposure, oldAutoShutterState, positionList, label, bfPath,
                            correctedZPosition, correctedXPosition, correctedYPosition);
                    if (listOfDrifts.size() < 7){
                        xCorrection = 0;
                        yCorrection = 0;
                    } else {
                        xyDriftsBRISKORB = listOfDrifts.get(0);
                        xyDriftsORBORB = listOfDrifts.get(1);
                        xyDriftsORBBRISK = listOfDrifts.get(2);
                        xyDriftsBRISKBRISK = listOfDrifts.get(3);
                        xyDriftsAKAZEBRISK = listOfDrifts.get(4);
                        xyDriftsAKAZEORB = listOfDrifts.get(5);
                        xyDriftsAKAZEAKAZE = listOfDrifts.get(6);

                        double[] drifts = new double[11];

                        switch (algoToUseMultiple) {
                            case "BRISKORB":
                                drifts = xyDriftsBRISKORB;
                                break;
                            case "ORBORB":
                                drifts = xyDriftsORBORB;
                                break;
                            case "ORBBRISK":
                                drifts = xyDriftsORBBRISK;
                                break;
                            case "BRISKBRISK":
                                drifts = xyDriftsBRISKBRISK;
                                break;
                            case "AKAZEBRISK":
                                drifts = xyDriftsAKAZEBRISK;
                                break;
                            case "AKAZEORB":
                                drifts = xyDriftsAKAZEORB;
                                break;
                            case "AKAZEAKAZE":
                                drifts = xyDriftsAKAZEAKAZE;
                                break;
                            default:
                                IJ.error("Unknown method of algorithm combination");
                        }

                        switch (driftCalcul) {
                            case "Mean":
                                xCorrection = drifts[0];
                                yCorrection = drifts[1];
                                threshold = 0.05;
                                break;
                            case "Median":
                                xCorrection = drifts[5];
                                yCorrection = drifts[6];
                                threshold = 0.05;
                                break;
                            case "Minimum Distance" :
                                xCorrection = drifts[7];
                                yCorrection = drifts[8];
                                threshold = 0.001;
                                break;
                            case "Harmonic Mean" :
                                xCorrection = drifts[9];
                                yCorrection = drifts[10];
                                threshold = 0.05;
                                break;
                            default:
                                IJ.error("Unknown method of correction");
                        }

                        if (Double.isNaN(xCorrection) || Double.isNaN(yCorrection)){
                            ReportingUtils.logMessage("Drift correction failed at position " + label + " timepoint " + timepoint);
                            xCorrection = 0;
                            yCorrection = 0;
                        } else if (Math.abs(xCorrection) < threshold) {
                            xCorrection = 0;
                        } else if (Math.abs(yCorrection) < threshold) {
                            yCorrection = 0;
                        }
                    }

                    ReportingUtils.logMessage("X Correction : " + xCorrection);
                    ReportingUtils.logMessage("Y Correction : " + yCorrection);

                    correctedXPosition = currentXPosition + xCorrection;
                    correctedYPosition = currentYPosition + yCorrection;
                }

                long endTime = new Date().getTime();
                long acquisitionTimeElapsed = endTime - startTime;
                ReportingUtils.logMessage("Acquisition duration in ms : " + acquisitionTimeElapsed);

                writeMultipleOutput(acquisitionTimeElapsed, label, prefix, oldX, oldY, oldZ,
                        currentXPosition, correctedXPosition, currentYPosition, correctedYPosition, correctedZPosition,
                        xyDriftsBRISKORB, xyDriftsORBORB, xyDriftsORBBRISK, xyDriftsBRISKBRISK,
                        xyDriftsAKAZEBRISK, xyDriftsAKAZEORB, xyDriftsAKAZEAKAZE);
            } else {
                double currentZPosition = oldZ;
                double[] drifts = new double[11];
                //Define current image as reference for the position if it does not exist
                if (!refImageDict.containsKey(label)) {
                    refImageDict.put(label, currentMat8Set);
                } else {
                    //Or calculate XY drift
                    imgRef_Mat = refImageDict.get(label);
                    int detector = getFeatureDetectorIndex(detectorAlgo);
                    int matcher = getDescriptorExtractorIndex(matcherAlgo);

                    drifts = calculateXYDrifts(currentMat8Set, detector, matcher, DescriptorMatcher.FLANNBASED,
                            oldROI, oldState, oldExposure, oldAutoShutterState,
                            positionList, label, bfPath, correctedZPosition, correctedXPosition, correctedYPosition);

                    switch (driftCalcul) {
                        case "Mean":
                            xCorrection = drifts[0];
                            yCorrection = drifts[1];
                            threshold = 0.05;
                            break;
                        case "Median":
                            xCorrection = drifts[5];
                            yCorrection = drifts[6];
                            threshold = 0.05;
                            break;
                        case "Minimum Distance" :
                            xCorrection = drifts[7];
                            yCorrection = drifts[8];
                            threshold = 0.001;
                            break;
                        case "Harmonic Mean" :
                            xCorrection = drifts[9];
                            yCorrection = drifts[10];
                            threshold = 0.05;
                            break;
                        default:
                            IJ.error("Unknown method of correction");
                    }

                    if (Double.isNaN(xCorrection) || Double.isNaN(yCorrection)){
                        ReportingUtils.logMessage("Drift correction failed at position " + label + " timepoint " + timepoint);
                        xCorrection = 0;
                        yCorrection = 0;
                    } else if (Math.abs(xCorrection) < threshold) {
                        xCorrection = 0;
                    } else if (Math.abs(yCorrection) < threshold) {
                        yCorrection = 0;
                    }

                    ReportingUtils.logMessage("X Correction : " + xCorrection);
                    ReportingUtils.logMessage("Y Correction : " + yCorrection);

                    correctedXPosition = currentXPosition + xCorrection;
                    correctedYPosition = currentYPosition + yCorrection;

                }

                long endTime = new Date().getTime();
                long acquisitionTimeElapsed = endTime - startTime;
                ReportingUtils.logMessage("Acquisition duration in ms : " + acquisitionTimeElapsed);

                writeOutput(acquisitionTimeElapsed, label, prefix, currentXPosition, correctedXPosition,
                        currentYPosition, correctedYPosition, currentZPosition, correctedZPosition,
                        drifts, intervalInMin);
            }

            //If XY Correction, new coordinates; else, corrected = current coordinates;
            setXYPosition(correctedXPosition, correctedYPosition);

            //Reference image incremental
            core_.waitForDevice(core_.getCameraDevice());
            core_.snapImage();
            TaggedImage newRefTaggedImage = core_.getTaggedImage();
            Mat newRefMat = convertTo8BitsMat(newRefTaggedImage);
            refImageDict.replace(label, newRefMat);
        }

        finalizeAcquisition(oldROI, oldState, oldExposure, oldAutoShutterState, positionList, label,
                bfPath, correctedZPosition, correctedXPosition, correctedYPosition);

        return correctedZPosition;
    }
    //*******//
    //Methods//
    //*******//

    public String getLabelOfPositions(PositionList positionList) {
        return positionList.getPosition(positionIndex).getLabel();
    }

    public int getFeatureDetectorIndex(String name){
        int index = -1;
        switch (name){
            case "AKAZE":
                index = FeatureDetector.AKAZE;
                break;
            case "ORB":
                index = FeatureDetector.ORB;
                break;
            case "BRISK":
                index = FeatureDetector.BRISK;
                break;
            default:
                ReportingUtils.logError("Can not handle this algorithm name");
        }
        return index;
    }

    public int getDescriptorExtractorIndex(String name){
        int index = -1;
        switch (name){
            case "AKAZE":
                index = DescriptorExtractor.AKAZE;
                break;
            case "ORB":
                index = DescriptorExtractor.ORB;
                break;
            case "BRISK":
                index = DescriptorExtractor.BRISK;
                break;
            default:
                ReportingUtils.logError("Can not handle this algorithm name");
        }
        return index;
    }

    //Write output file when testing all algorithms
    public void writeMultipleOutput(long acquisitionDuration, String label, String prefix, double oldX,
                                    double oldY, double oldZ, double currentXPosition, double correctedXPosition, double currentYPosition,
                                    double correctedYPosition, double correctedZPosition, double[] xyDriftsBRISKORB,
                                    double[] xyDriftsORBORB, double[] xyDriftsORBBRISK, double[] xyDriftsBRISKBRISK,
                                    double[] xyDriftsAKAZEAKAZE, double[] xyDriftsAKAZEBRISK, double[] xyDriftsAKAZEORB) {

        File f1 = new File(savingPath + prefix + "_" + label + "_Stats" + ".csv");
        FileWriter fw = null;
        if (!f1.exists()) {
            try {
                f1.createNewFile();
                fw = new FileWriter(f1);
            } catch (IOException e) {
                e.printStackTrace();
                ReportingUtils.showError("Unable to create file");
            }
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

                    "medianXdisplacementBRISKORB", "medianYdisplacementBRISKORB", "medianXdisplacementORBORB", "medianYdisplacementORBORB",
                    "medianXdisplacementORBBRISK", "medianYdisplacementORBBRISK", "medianXdisplacementBRISKBRISK", "medianYdisplacementBRISKBRISK",
                    "medianXdisplacementAKAZEBRISK", "medianYdisplacementAKAZEBRISK", "medianXdisplacementAKAZEORB", "medianYdisplacementAKAZEORB",
                    "medianXdisplacementAKAZEAKAZE", "medianYdisplacementAKAZEAKAZE",


                    "minXdisplacementBRISKORB", "minYdisplacementBRISKORB", "minXdisplacementORBORB", "minYdisplacementORBORB",
                    "minXdisplacementORBBRISK", "minYdisplacementORBBRISK", "minXdisplacementBRISKBRISK", "minYdisplacementBRISKBRISK",
                    "minXdisplacementAKAZEBRISK", "minYdisplacementAKAZEBRISK", "minXdisplacementAKAZEORB", "minYdisplacementAKAZEORB",
                    "minXdisplacementAKAZEAKAZE", "minYdisplacementAKAZEAKAZE",

                    "harmonicMeanXdisplacementBRISKORB", "harmonicMeanYdisplacementBRISKORB", "harmonicMeanXdisplacementORBORB", "harmonicMeanYdisplacementORBORB",
                    "harmonicMeanXdisplacementORBBRISK", "harmonicMeanYdisplacementORBBRISK", "harmonicMeanXdisplacementBRISKBRISK", "harmonicMeanYdisplacementBRISKBRISK",
                    "harmonicMeanXdisplacementAKAZEBRISK", "harmonicMeanYdisplacementAKAZEBRISK", "harmonicMeanXdisplacementAKAZEORB", "harmonicMeanYdisplacementAKAZEORB",
                    "harmonicMeanXdisplacementAKAZEAKAZE", "harmonicMeanYdisplacementAKAZEAKAZE"

            } ;

            try {
                fw.write(String.join(",", headersOfFile) + System.lineSeparator());
                fw.close();
            } catch (IOException e) {
                e.printStackTrace();
                ReportingUtils.showError("Unable to write/close file");
            }
        } else {
            double meanXdisplacementBRISKORB = xyDriftsBRISKORB[0];
            double meanYdisplacementBRISKORB = xyDriftsBRISKORB[1];
            double numberOfMatchesBRISKORB = xyDriftsBRISKORB[2];
            double numberOfGoodMatchesBRISKORB = xyDriftsBRISKORB[3];
            double algorithmDurationBRISKORB = xyDriftsBRISKORB[4];
            double medianXDisplacementBRISKORB = xyDriftsBRISKORB[5];
            double medianYDisplacementBRISKORB = xyDriftsBRISKORB[6];
            double minXDisplacementBRISKORB = xyDriftsBRISKORB[7];
            double minYDisplacementBRISKORB = xyDriftsBRISKORB[8];
            double harmonicMeanXDisplacementBRISKORB = xyDriftsBRISKORB[9];
            double harmonicMeanYDisplacementBRISKORB = xyDriftsBRISKORB[10];

            double meanXdisplacementORBORB = xyDriftsORBORB[0];
            double meanYdisplacementORBORB = xyDriftsORBORB[1];
            double numberOfMatchesORBORB = xyDriftsORBORB[2];
            double numberOfGoodMatchesORBORB = xyDriftsORBORB[3];
            double algorithmDurationORBORB = xyDriftsORBORB[4];
            double medianXDisplacementORBORB = xyDriftsORBORB[5];
            double medianYDisplacementORBORB = xyDriftsORBORB[6];
            double minXDisplacementORBORB = xyDriftsORBORB[7];
            double minYDisplacementORBORB = xyDriftsORBORB[8];
            double harmonicMeanXDisplacementORBORB = xyDriftsORBORB[9];
            double harmonicMeanYDisplacementORBORB = xyDriftsORBORB[10];

            double meanXdisplacementORBBRISK = xyDriftsORBBRISK[0];
            double meanYdisplacementORBBRISK = xyDriftsORBBRISK[1];
            double numberOfMatchesORBBRISK = xyDriftsORBBRISK[2];
            double numberOfGoodMatchesORBBRISK = xyDriftsORBBRISK[3];
            double algorithmDurationORBBRISK = xyDriftsORBBRISK[4];
            double medianXDisplacementORBBRISK = xyDriftsORBBRISK[5];
            double medianYDisplacementORBBRISK = xyDriftsORBBRISK[6];
            double minXDisplacementORBBRISK = xyDriftsORBBRISK[7];
            double minYDisplacementORBBRISK = xyDriftsORBBRISK[8];
            double harmonicMeanXDisplacementORBBRISK = xyDriftsORBBRISK[9];
            double harmonicMeanYDisplacementORBBRISK = xyDriftsORBBRISK[10];

            double meanXdisplacementBRISKBRISK = xyDriftsBRISKBRISK[0];
            double meanYdisplacementBRISKBRISK = xyDriftsBRISKBRISK[1];
            double numberOfMatchesBRISKBRISK = xyDriftsBRISKBRISK[2];
            double numberOfGoodMatchesBRISKBRISK = xyDriftsBRISKBRISK[3];
            double algorithmDurationBRISKBRISK = xyDriftsBRISKBRISK[4];
            double medianXDisplacementBRISKBRISK = xyDriftsBRISKBRISK[5];
            double medianYDisplacementBRISKBRISK = xyDriftsBRISKBRISK[6];
            double minXDisplacementBRISKBRISK = xyDriftsBRISKBRISK[7];
            double minYDisplacementBRISKBRISK = xyDriftsBRISKBRISK[8];
            double harmonicMeanXDisplacementBRISKBRISK = xyDriftsBRISKBRISK[9];
            double harmonicMeanYDisplacementBRISKBRISK = xyDriftsBRISKBRISK[10];

            double meanXdisplacementAKAZEBRISK = xyDriftsAKAZEBRISK[0];
            double meanYdisplacementAKAZEBRISK = xyDriftsAKAZEBRISK[1];
            double numberOfMatchesAKAZEBRISK = xyDriftsAKAZEBRISK[2];
            double numberOfGoodMatchesAKAZEBRISK = xyDriftsAKAZEBRISK[3];
            double algorithmDurationAKAZEBRISK = xyDriftsAKAZEBRISK[4];
            double medianXDisplacementAKAZEBRISK = xyDriftsAKAZEBRISK[5];
            double medianYDisplacementAKAZEBRISK = xyDriftsAKAZEBRISK[6];
            double minXDisplacementAKAZEBRISK = xyDriftsAKAZEBRISK[7];
            double minYDisplacementAKAZEBRISK = xyDriftsAKAZEBRISK[8];
            double harmonicMeanXDisplacementAKAZEBRISK = xyDriftsAKAZEBRISK[9];
            double harmonicMeanYDisplacementAKAZEBRISK = xyDriftsAKAZEBRISK[10];

            double meanXdisplacementAKAZEORB = xyDriftsAKAZEORB[0];
            double meanYdisplacementAKAZEORB = xyDriftsAKAZEORB[1];
            double numberOfMatchesAKAZEORB = xyDriftsAKAZEORB[2];
            double numberOfGoodMatchesAKAZEORB = xyDriftsAKAZEORB[3];
            double algorithmDurationAKAZEORB = xyDriftsAKAZEORB[4];
            double medianXDisplacementAKAZEORB = xyDriftsAKAZEORB[5];
            double medianYDisplacementAKAZEORB = xyDriftsAKAZEORB[6];
            double minXDisplacementAKAZEORB = xyDriftsAKAZEORB[7];
            double minYDisplacementAKAZEORB = xyDriftsAKAZEORB[8];
            double harmonicMeanXDisplacementAKAZEORB = xyDriftsAKAZEORB[9];
            double harmonicMeanYDisplacementAKAZEORB = xyDriftsAKAZEORB[10];

            double meanXdisplacementAKAZEAKAZE = xyDriftsAKAZEAKAZE[0];
            double meanYdisplacementAKAZEAKAZE = xyDriftsAKAZEAKAZE[1];
            double numberOfMatchesAKAZEAKAZE = xyDriftsAKAZEAKAZE[2];
            double numberOfGoodMatchesAKAZEAKAZE = xyDriftsAKAZEAKAZE[3];
            double algorithmDurationAKAZEAKAZE = xyDriftsAKAZEAKAZE[4];
            double medianXDisplacementAKAZEAKAZE = xyDriftsAKAZEAKAZE[5];
            double medianYDisplacementAKAZEAKAZE = xyDriftsAKAZEAKAZE[6];
            double minXDisplacementAKAZEAKAZE = xyDriftsAKAZEAKAZE[7];
            double minYDisplacementAKAZEAKAZE = xyDriftsAKAZEAKAZE[8];
            double harmonicMeanXDisplacementAKAZEAKAZE = xyDriftsAKAZEAKAZE[9];
            double harmonicMeanYDisplacementAKAZEAKAZE = xyDriftsAKAZEAKAZE[10];

            FileWriter fw1 = null;
            try {
                new FileWriter(f1, true);
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

                        + minXDisplacementBRISKORB + "," + minYDisplacementBRISKORB + "," + minXDisplacementORBORB + "," + minYDisplacementORBORB + ","
                        + minXDisplacementORBBRISK + "," + minYDisplacementORBBRISK + "," + minXDisplacementBRISKBRISK + "," + minYDisplacementBRISKBRISK + ","
                        + minXDisplacementAKAZEBRISK + "," + minYDisplacementAKAZEBRISK + "," + minXDisplacementAKAZEORB + "," + minYDisplacementAKAZEORB + ","
                        + minXDisplacementAKAZEAKAZE + "," + minYDisplacementAKAZEAKAZE + ","

                        + harmonicMeanXDisplacementBRISKORB + "," + harmonicMeanYDisplacementBRISKORB + "," + harmonicMeanXDisplacementORBORB + "," + harmonicMeanYDisplacementORBORB + ","
                        + harmonicMeanXDisplacementORBBRISK + "," + harmonicMeanYDisplacementORBBRISK + "," + harmonicMeanXDisplacementBRISKBRISK + "," + harmonicMeanYDisplacementBRISKBRISK + ","
                        + harmonicMeanXDisplacementAKAZEBRISK + "," + harmonicMeanYDisplacementAKAZEBRISK + "," + harmonicMeanXDisplacementAKAZEORB + "," + harmonicMeanYDisplacementAKAZEORB + ","
                        + harmonicMeanXDisplacementAKAZEAKAZE + "," + harmonicMeanYDisplacementAKAZEAKAZE

                        + System.lineSeparator());
                fw1.close();
            } catch (IOException e) {
                e.printStackTrace();
                ReportingUtils.showError("Unable to add lines to file");
            }
        }
    }

    //Write output file for one algorithm
    public void writeOutput(long acquisitionDuration, String label, String prefix, double currentXPosition, double correctedXPosition,
                            double currentYPosition, double correctedYPosition,
                            double currentZPosition, double correctedZPosition, double[] xyDrifts, double intervalInMin_) {

        File f1 = new File(savingPath + prefix + "_" + label + "_Stats" + ".csv");
        FileWriter fw = null;
        if (!f1.exists()) {
            try {
                f1.createNewFile();
                fw = new FileWriter(f1);
            } catch (IOException e) {
                e.printStackTrace();
                ReportingUtils.showError("Unable to create file");
            }
            String[] headersOfFile = new String[]{"currentXPosition", "correctedXPosition",
                    "currentYPosition", "correctedYPosition",

                    "currentZPosition" , "correctedZPosition",

                    "meanXdisplacement", "meanYdisplacement",

                    "medianXdisplacement", "medianYdisplacement",

                    "minXdisplacement", "minYdisplacement",

                    "harmonicMeanXdisplacement", "harmonicMeanYdisplacement",

                    "numberOfMatches", "numberOfGoodMatches",

                    "algorithmDuration(ms)", "acquisitionDuration(ms)", "intervalInMin"

            } ;

            try {
                fw.write(String.join(",", headersOfFile) + System.lineSeparator());
                fw.close();
            } catch (IOException e) {
                e.printStackTrace();
                ReportingUtils.showError("Unable to write/close file");
            }

        } else {
            double meanXdisplacement = xyDrifts[0];
            double meanYdisplacement = xyDrifts[1];
            double numberOfMatches = xyDrifts[2];
            double numberOfGoodMatches = xyDrifts[3];
            double algorithmDuration = xyDrifts[4];
            double medianXDisplacement = xyDrifts[5];
            double medianYDisplacement = xyDrifts[6];
            double minXDisplacement = xyDrifts[7];
            double minYDisplacement = xyDrifts[8];
            double harmonicMeanXDisplacement = xyDrifts[9];
            double harmonicMeanYDisplacement = xyDrifts[10];

            FileWriter fw1 = null;
            try {
                fw1 = new FileWriter(f1, true);
                fw1.write(currentXPosition + "," + correctedXPosition + ","

                        + currentYPosition + "," + correctedYPosition + ","

                        + currentZPosition + "," + correctedZPosition  + ","

                        + meanXdisplacement + "," + meanYdisplacement + ","

                        + medianXDisplacement + "," + medianYDisplacement + ","

                        + minXDisplacement + "," + minYDisplacement + ","

                        + harmonicMeanXDisplacement + "," + harmonicMeanYDisplacement + ","

                        + numberOfMatches + "," + numberOfGoodMatches + ","

                        + algorithmDuration + "," + acquisitionDuration + "," + intervalInMin_

                        + System.lineSeparator());
                fw1.close();
            } catch (IOException e) {
                e.printStackTrace();
                ReportingUtils.showError("Unable to add lines to file");
            }
        }
    }

    //Reinitialization methods

    //Reinitialize counters and dictionaries
    public void resetParameters(){
        refImageDict = new HashMap<>();
        oldPositionsDict = new HashMap<>();
        positionIndex = 0;
        store = null;
        imageCount = 0;
        timepoint = 0;
        IJ.log("BF AutoFocus internal parameters have been reset");
    }

    //Reinitialize origin ROI and all other parameters
    public void resetInitialMicroscopeCondition(Rectangle oldROI, Configuration oldState, double oldExposure,
                                                boolean oldAutoShutterState) {
        core_.setAutoShutter(oldAutoShutterState);

        if (cropFactor < 1.0) {
            try {
                studio_.app().setROI(oldROI);
                core_.waitForDevice(core_.getCameraDevice());
            } catch (Exception e) {
                e.printStackTrace();
                ReportingUtils.showError("Unable to reset ROI");
            }
        }

        if (oldState != null) {
            core_.setSystemState(oldState);
        }

        try {
            core_.setExposure(oldExposure);
        } catch (Exception e) {
            e.printStackTrace();
            ReportingUtils.showError("Unable to reset exposure");
        }
    }

    public void finalizeAcquisition(Rectangle oldROI, Configuration oldState, double oldExposure, boolean oldAutoShutterState,
                                    PositionList positionList, String label, String bfPath, double correctedZPosition,
                                    double correctedXPosition, double correctedYPosition) {
        //Reset conditions
        resetInitialMicroscopeCondition(oldROI, oldState, oldExposure, oldAutoShutterState);

        //Set to the focus
        setZPosition(correctedZPosition);

        //Refresh positions in position dictionary
        refreshOldXYZposition(correctedXPosition, correctedYPosition, correctedZPosition, label);

        //Save datastore if acquisition stopped/ all timepoints for all positions have been acquired
        if (!studio_.acquisitions().isAcquisitionRunning() ||
                (timepoint == studio_.acquisitions().getAcquisitionSettings().numFrames-1
                        && store.getAxisLength("position")-1 == positionIndex)){
            if (save.contentEquals("Yes")) {
                SummaryMetadata summary = store.getSummaryMetadata();
                if (summary == null) {
                    // Create dummy summary metadata just for saving.
                    summary = (new DefaultSummaryMetadata.Builder()).build();
                }
                // Insert intended dimensions if they aren't already present.
                if (summary.getIntendedDimensions() == null) {
                    DefaultCoords.Builder builder = new DefaultCoords.Builder();
                    for (String axis : store.getAxes()) {
                        builder.index(axis, store.getAxisLength(axis));
                    }
                    summary = summary.copy().intendedDimensions(builder.build()).build();
                }

                //Add summary metadata to data
                try {
                    store.setSummaryMetadata(summary);
                } catch (DatastoreFrozenException | DatastoreRewriteException e) {
                    e.printStackTrace();
                    ReportingUtils.logMessage("Unable to set metadata");
                }

                //Save datastore
                store.freeze();
                store.save(Datastore.SaveMode.MULTIPAGE_TIFF, bfPath+"_ordered");
                ReportingUtils.logMessage("Datastore saved");
                store.close();

//                try {
//                    studio_.core().clearCircularBuffer();
//                } catch (Exception e) {
//                    e.printStackTrace();
//                    ReportingUtils.logMessage("Unable to clear circular buffer");
//                }

                if (show.contentEquals("Yes")) {
                    studio_.displays().manage(store);
                }
            }
            resetParameters();

            //Increment timepoint and positionIndex if acquisition still running
        } else {
            if (positionList.getNumberOfPositions() == 0) {
                timepoint++;
            } else if(positionList.getNumberOfPositions() == 1){
                timepoint ++;
            } else {
                positionIndex++;
                if (positionIndex == positionList.getNumberOfPositions()){
                    positionIndex = 0;
                    timepoint ++;
                }
            }
        }
    }

    //XYZ-Methods

    public double[] getXYZPosition(String label) {
        return oldPositionsDict.get(label);
    }

    public void refreshOldXYZposition(double correctedXPosition, double correctedYPosition, double correctedZPosition, String label) {
        double[] refreshedXYZposition = new double[3];
        refreshedXYZposition[0] = correctedXPosition;
        refreshedXYZposition[1] = correctedYPosition;
        refreshedXYZposition[2] = correctedZPosition;
        oldPositionsDict.replace(label, refreshedXYZposition);
    }

    public void setToLastCorrectedPosition(double oldX, double oldY, double oldZ) {
        setXYPosition(oldX, oldY);
        setZPosition(oldZ);
    }

    //Z-Methods

    public double getZPosition() {
        String focusDevice = core_.getFocusDevice();
        double z = 0;
        try {
            z = core_.getPosition(focusDevice);
        } catch (Exception e) {
            e.printStackTrace();
            ReportingUtils.showError("Unable to get Z Position");
        }
        return z;
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

    public static double[] calculateZPositions(double searchRange, double step, double startZUm){
        double lower = startZUm - searchRange/2;
        int nstep  = new Double(searchRange/step).intValue() + 1;
        double[] zpos = new double[nstep];
        for (int p = 0; p < nstep; p++){
            zpos[p] = lower + p * step;
        }
        return zpos;
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

    public double calculateZFocus(double oldZ, boolean save) {
        double[] zPositions = calculateZPositions(searchRange, step, oldZ);
        double[] stdAtZPositions = new double[zPositions.length];
        TaggedImage currentImg = null;

        for (int i =0; i< zPositions.length ;i++){
            setZPosition(zPositions[i]);
            try {
                core_.waitForDevice(core_.getCameraDevice());
                core_.snapImage();
                currentImg = core_.getTaggedImage();
            } catch (Exception e) {
                e.printStackTrace();
                ReportingUtils.showError("Cannot take snapshot");
            }
            imageCount++;
            assert currentImg != null;
            Metadata metadata = DefaultMetadata.legacyFromJSON(currentImg.tags);
            PositionList posList = studio_.positions().getPositionList();
            if (posList.getNumberOfPositions()>0){
                metadata = metadata.copy().positionName(posList.getPosition(positionIndex).getLabel()).build();
            }
            Image img = null;
            try {
                img = studio_.data().convertTaggedImage(currentImg,
                        studio_.data().getCoordsBuilder().z(i).channel(0).stagePosition(positionIndex).time(timepoint).build(),
                        metadata);
                if (save){
                    assert store != null;
                    store.putImage(img);
                }
            } catch (JSONException | DatastoreRewriteException | DatastoreFrozenException e) {
                e.printStackTrace();
                ReportingUtils.showError("Unable to save current z image at " + i);
            }
            stdAtZPositions[i] = studio_.data().ij().createProcessor(img).getStatistics().stdDev;
        }
        int rawIndex = getZfocus(stdAtZPositions);
        return optimizeZFocus(rawIndex, stdAtZPositions, zPositions);
    }

    public void setZPosition(double z) {
        String focusDevice = core_.getFocusDevice();
        try {
            core_.setPosition(focusDevice, z);
            core_.waitForDevice(focusDevice);
        } catch (Exception e) {
            e.printStackTrace();
            ReportingUtils.showError("  Unable to set Z Position");
        }
    }

    //XY-Methods

    public double[] calculateXYDrifts(Mat currentImgMat, Integer detectorAlgo, Integer descriptorExtractor, Integer descriptorMatcher,
                                      Rectangle oldROI, Configuration oldState, double oldExposure, boolean oldAutoShutterState,
                                      PositionList positionList, String label, String bfPath, double correctedZPosition,
                                      double correctedXPosition, double correctedYPosition) {

        ExecutorService es = Executors.newSingleThreadExecutor();
        Future job = es.submit(new ThreadAttribution(imgRef_Mat, currentImgMat, calibration,
                intervalInMin, umPerStep, detectorAlgo, descriptorExtractor, descriptorMatcher));
        double[] xyDrifts = new double[11];
        try {
            xyDrifts = (double[]) job.get();
        } catch (Exception e1) {
            e1.printStackTrace();
            finalizeAcquisition(oldROI, oldState, oldExposure, oldAutoShutterState, positionList, label, bfPath,
                    correctedZPosition, correctedXPosition, correctedYPosition);
            ReportingUtils.logMessage("Error in algorithm; initial microscope condition have been reset");
        }

        es.shutdown();
        try {
            es.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            try {
                finalizeAcquisition(oldROI, oldState, oldExposure, oldAutoShutterState, positionList, label, bfPath,
                        correctedZPosition, correctedXPosition, correctedYPosition);
                ReportingUtils.logMessage("Calculation took too much time; initial microscope condition have been reset;" +
                        "pass to next time point");
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        }

        return xyDrifts;
    }

    public List<double[]> calculateMultipleXYDrifts(Mat currentImgMat, Integer detectorAlgo1, Integer detectorAlgo2, Integer detectorAlgo3,
                                                    Integer descriptorExtractor1, Integer descriptorExtractor2, Integer descriptorExtractor3,
                                                    Integer descriptorMatcher, Rectangle oldROI, Configuration oldState,
                                                    double oldExposure, boolean oldAutoShutterState,
                                                    PositionList positionList, String label, String bfPath, double correctedZPosition,
                                                    double correctedXPosition, double correctedYPosition){
        int nThread = Runtime.getRuntime().availableProcessors() - 2;
        ExecutorService es = Executors.newFixedThreadPool(nThread);
        Future[] jobs = new Future[7];
        //BRISK-ORB
        jobs[0] = es.submit(new ThreadAttribution(imgRef_Mat, currentImgMat, calibration,
                intervalInMin, umPerStep, detectorAlgo1, descriptorExtractor2, descriptorMatcher));
        //ORB-ORB
        jobs[1] = es.submit(new ThreadAttribution(imgRef_Mat, currentImgMat, calibration,
                intervalInMin, umPerStep, detectorAlgo2, descriptorExtractor2, descriptorMatcher));
        //ORB-BRISK
        jobs[2] = es.submit(new ThreadAttribution(imgRef_Mat, currentImgMat, calibration,
                intervalInMin, umPerStep, detectorAlgo2, descriptorExtractor1, descriptorMatcher));
        //BRISK-BRISK
        jobs[3] = es.submit(new ThreadAttribution(imgRef_Mat, currentImgMat, calibration,
                intervalInMin, umPerStep, detectorAlgo1, descriptorExtractor1, descriptorMatcher));
        //AKAZE-BRISK
        jobs[4] = es.submit(new ThreadAttribution(imgRef_Mat, currentImgMat, calibration,
                intervalInMin, umPerStep, detectorAlgo3, descriptorExtractor1, descriptorMatcher));
        //AKAZE-ORB
        jobs[5] = es.submit(new ThreadAttribution(imgRef_Mat, currentImgMat, calibration,
                intervalInMin, umPerStep, detectorAlgo3, descriptorExtractor2, descriptorMatcher));
        //AKAZE-AKAZE
        jobs[6] = es.submit(new ThreadAttribution(imgRef_Mat, currentImgMat, calibration,
                intervalInMin, umPerStep, detectorAlgo3, descriptorExtractor3, descriptorMatcher));

        List<double[]> drifts = new ArrayList<>();
        double[] currentRes = null;
        int algoIndex = -1;
        try {
            for (int i = 0; i < jobs.length; i++) {
                currentRes = (double[]) jobs[i].get();
                algoIndex = i;
                drifts.add(i, currentRes);
            }
        } catch (InterruptedException | ExecutionException e) {
            try {
                for (double d : currentRes){
                    ReportingUtils.logMessage("Error in algo " + algoIndex + "_" + d);
                }
                finalizeAcquisition(oldROI, oldState, oldExposure, oldAutoShutterState, positionList, label, bfPath,
                        correctedZPosition, correctedXPosition, correctedYPosition);
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        }
        es.shutdown();
        try{
            es.awaitTermination(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return drifts;
    }

    public void setXYPosition(double x, double y) {
        assert x != 0;
        assert y != 0;
        String xyDevice = core_.getXYStageDevice();
        try {
            core_.setXYPosition(x,y);
            core_.waitForDevice(xyDevice);
        } catch (Exception e) {
            e.printStackTrace();
            ReportingUtils.showError("Unable to set XY position");
        }
    }

    //Converters

    //Convert MM TaggedImage to OpenCV Mat
    public static Mat convertToMat(TaggedImage img){
        int width = 0;
        int height = 0;
        try {
            width = img.tags.getInt("Width");
            height = img.tags.getInt("Height");
        } catch (JSONException e) {
            e.printStackTrace();
            ReportingUtils.showError("Unable to get width/height");
        }
        Mat mat = new Mat(height, width, CvType.CV_16UC1);
        mat.put(0,0, (short[]) img.pix);
        return mat;
    }

    //Convert MM TaggedImage to OpenCV 8 bits Mat
    public static Mat convertTo8BitsMat(TaggedImage taggedImage) {
        Mat mat16 = convertToMat(taggedImage);
        Mat mat8 = new Mat(mat16.cols(), mat16.rows(), CvType.CV_8UC1);
        Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(mat16);
        double min = minMaxResult.minVal;
        double max = minMaxResult.maxVal;
        mat16.convertTo(mat8, CvType.CV_8UC1, 255/(max-min));
        return DriftCorrection.equalizeImages(mat8);
    }

//    //Convert MM Short Processor to OpenCV Mat
//    public static Mat toMat(ShortProcessor sp) {
//        final int h = sp.getHeight();
//        final int w = sp.getWidth();
//        Mat mat = new Mat(h, w, CvType.CV_16UC1);
//        mat.put(0,0, (short[]) sp.getPixels());
//        Mat res = new Mat(h, w, CvType.CV_8UC1);
//        mat.convertTo(res, CvType.CV_8UC1, alpha);
//    return DriftCorrection.equalizeImages(res);
//    }

    //Methods overriding
    @Override
    public double incrementalFocus() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int getNumberOfImages() {
        return imageCount;
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

    //********************************************************************************//
    //*************************** Class for multithreading ***************************//
    //********************************************************************************//
    public class ThreadAttribution implements Callable<double[]> {

        public Mat img1_;
        public Mat img2_;
        public double calibration_;
        public double intervalInMs_;
        public double umPerStep_;
        public Integer detectorAlgo_;
        public Integer descriptorExtractor_;
        public Integer descriptorMatcher_;

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
        public double[] call() throws Exception {
            return DriftCorrection.driftCorrection(img1_, img2_, calibration_, intervalInMs_,
                    umPerStep_, detectorAlgo_, descriptorExtractor_, descriptorMatcher_);
        }
    }
}

