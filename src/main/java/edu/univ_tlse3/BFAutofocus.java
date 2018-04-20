package edu.univ_tlse3;

import ij.IJ;
import ij.process.ImageProcessor;
import mmcorej.*;
import org.json.JSONException;
import org.micromanager.AutofocusPlugin;
import org.micromanager.PositionList;
import org.micromanager.Studio;
import org.micromanager.data.Coords;
import org.micromanager.data.Datastore;
import org.micromanager.data.Image;
import org.micromanager.internal.utils.*;
import org.opencv.core.Core;
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
import java.util.*;
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
    private static final String SHOWIMAGES_TEXT = "ShowImages";
    private static final String SAVEIMGS_TEXT = "SaveImages";
    private static final String XY_CORRECTION_TEXT = "Correct XY at same time";
    private static final String DETECTORALGO_TEXT = "Feature detector algorithm";
    private static final String MATCHERALGO_TEXT = "Matches extractor algorithm";
    private static final String[] DETECTORALGO_VALUES = {"AKAZE", "BRISK", "ORB"};
    private static final String[] MATCHERALGO_VALUES = {"AKAZE", "BRISK", "ORB"};
    private static final String[] SHOWIMAGES_VALUES = {"Yes", "No"};
    private static final String[] SAVEIMAGES_VALUES = {"Yes", "No"};
    private static final String STEP_SIZE = "Step_size";
    private static final String[] XY_CORRECTION_VALUES = {"Yes", "No"};
    private static final String UMPERSTEP = "µm displacement allowed per time point";
    private static final String Z_OFFSET = "Z offset";

    //Set default parameters
    private double searchRange = 5;
    private double cropFactor = 1;
    private String channel = "BF";
    private double exposure = 50;
    private String show = "No";
    private String save = "Yes";
    private int imageCount = 0;
    private int timepoint = 0;
    private double step = 0.3;
    private String xy_correction = "No";
    private Map<String, Mat> refImageDict = new HashMap<>();
    private Map<String, double[]> oldPositionsDict = new HashMap<>();
    private double umPerStep = 15;
    private String detectorAlgo = "AKAZE";
    private String matcherAlgo = "BRISK";
    private double zOffset = -1;

    //Global variables
    private Studio studio_;
    private CMMCore core_;
    private Mat imgRef_Mat = null;
    private double calibration = 0;
    private double intervalInMin = 0;
    private int positionIndex = 0;
    private String savingPath;

    //Begin autofocus
    public BFAutofocus() {
        super.createProperty(SEARCH_RANGE, NumberUtils.doubleToDisplayString(searchRange));
        super.createProperty(CROP_FACTOR, NumberUtils.doubleToDisplayString(cropFactor));
        super.createProperty(EXPOSURE, NumberUtils.doubleToDisplayString(exposure));
        super.createProperty(Z_OFFSET, NumberUtils.doubleToDisplayString(zOffset));
        super.createProperty(SHOWIMAGES_TEXT, show, SHOWIMAGES_VALUES);
        super.createProperty(XY_CORRECTION_TEXT, xy_correction, XY_CORRECTION_VALUES);
        super.createProperty(DETECTORALGO_TEXT, detectorAlgo, DETECTORALGO_VALUES);
        super.createProperty(MATCHERALGO_TEXT, matcherAlgo, MATCHERALGO_VALUES);
        super.createProperty(STEP_SIZE, NumberUtils.doubleToDisplayString(step));
        super.createProperty(CHANNEL, channel);
        super.createProperty(UMPERSTEP, NumberUtils.doubleToDisplayString(umPerStep));
        super.createProperty(SAVEIMGS_TEXT, save, SAVEIMAGES_VALUES);
        nu.pattern.OpenCV.loadShared();
    }

    @Override
    public void applySettings() {
        try {
            searchRange = NumberUtils.displayStringToDouble(getPropertyValue(SEARCH_RANGE));
            cropFactor = NumberUtils.displayStringToDouble(getPropertyValue(CROP_FACTOR));
            cropFactor = MathFunctions.clip(0.01, cropFactor, 1.0);
            exposure = NumberUtils.displayStringToDouble(getPropertyValue(EXPOSURE));
            zOffset = NumberUtils.displayStringToDouble(getPropertyValue(Z_OFFSET));
            show = getPropertyValue(SHOWIMAGES_TEXT);
            xy_correction = getPropertyValue(XY_CORRECTION_TEXT);
            detectorAlgo = getPropertyValue(DETECTORALGO_TEXT);
            matcherAlgo = getPropertyValue(MATCHERALGO_TEXT);
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
            label = positionList.generateLabel();
        }else{
            label = getLabelOfPositions(positionList);
        }
        ReportingUtils.logMessage("Label Position : " + label + " at time point : " + timepoint);

        //Incrementation of position counter; does not work at another place
        positionIndex += 1;

        double[] oldCorrectedPositions;
        double oldX = core_.getXPosition();
        double oldY = core_.getYPosition();
        double oldZ = getZPosition();

        //Define positions if it does not exist
        if (!oldPositionsDict.containsKey(label)) {
            double[] currentPositions = new double[3];
            currentPositions[0] = oldX;
            currentPositions[1] = oldY;
            currentPositions[2] = oldZ;
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
        double correctedZPosition = calculateZFocus(oldZ, label, timepoint, save.contentEquals("Yes"));
        ReportingUtils.logMessage("Corrected Z Position : " + correctedZPosition);
        //Set to the focus
        setZPosition(correctedZPosition + zOffset);

        //Get an image to define reference image, for each position
        core_.waitForDevice(core_.getCameraDevice());
        core_.snapImage();
        TaggedImage taggedImagePosition = core_.getTaggedImage();
        Mat currentMat8Set = convertTo8BitsMat(taggedImagePosition);

        //Calculation of XY Drifts only if the parameter "Correct XY at same time" is set to Yes;
        double currentXPosition = core_.getXPosition();
        double currentYPosition = core_.getYPosition();

        double correctedXPosition = currentXPosition;
        double correctedYPosition = currentYPosition;

        double xCorrection = 0;
        double yCorrection = 0;

        if (xy_correction.contentEquals("Yes")){
            double[] drifts = new double[11];
            //Define current image as reference for the position if it does not exist
            if (!refImageDict.containsKey(label)) {
                refImageDict.put(label, currentMat8Set);
            } else {
                //Or calculate XY drift
                imgRef_Mat = refImageDict.get(label);
                int detector = getFeatureDetectorIndex(detectorAlgo);
                int matcher = getDescriptorExtractorIndex(matcherAlgo);
                ReportingUtils.logMessage("FeatureDetector : " + detector);

                //Get Correction to apply : 0-1 = x/y drifts; 3-4 = matcher size ; 5 = acquisition duration
                drifts = calculateXYDrifts(currentMat8Set, detector, matcher, DescriptorMatcher.FLANNBASED,
                      oldROI, oldState, oldExposure, oldAutoShutterState, DriftCorrection.MEAN);
                xCorrection = drifts[0];
                yCorrection = drifts[1];
                if (Double.isNaN(xCorrection) || Double.isNaN(yCorrection)){
                    ReportingUtils.logError("Nan is found with algorithm : " + matcher + "_" + "detector "+ detector);
                    xCorrection = 0;
                    yCorrection = 0;
                }
                correctedXPosition = currentXPosition + xCorrection;
                correctedYPosition = currentYPosition + yCorrection;

            }
            long endTime = new Date().getTime();
            long acquisitionTimeElapsed = endTime - startTime;
            ReportingUtils.logMessage("Acquisition duration in ms : " + acquisitionTimeElapsed);

            writeOutput(acquisitionTimeElapsed, label, prefix, currentXPosition, correctedXPosition,
                    currentYPosition, correctedYPosition, oldZ, correctedZPosition,
                    drifts, intervalInMin);

            setXYPosition(correctedXPosition, correctedYPosition);

            if (xCorrection != 0 && yCorrection != 0) {
                //Reference image incremental
                core_.waitForDevice(core_.getCameraDevice());
                core_.snapImage();
                TaggedImage newRefTaggedImage = core_.getTaggedImage();
                Mat newRefMat = convertTo8BitsMat(newRefTaggedImage);
                refImageDict.replace(label, newRefMat);
            }
        }

        //Reset conditions
        resetInitialMicroscopeCondition(oldROI, oldState, oldExposure, oldAutoShutterState);

        //Set to the focus
        setZPosition(correctedZPosition);

        //Refresh positions in position dictionary
        refreshOldXYZposition(correctedXPosition, correctedYPosition, correctedZPosition, label);

        if (!studio_.acquisitions().isAcquisitionRunning() ||
                timepoint >= studio_.acquisitions().getAcquisitionSettings().numFrames){
            resetParameters();
        }

        return correctedZPosition;
    }


    //Methods
    private void resetParameters(){
        refImageDict = new HashMap<>();
        oldPositionsDict = new HashMap<>();
        imageCount = 0;
        timepoint = 0;
        IJ.log("BF AutoFocus internal parameters have been reset");
    }

    private int getFeatureDetectorIndex(String name){
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

    private int getDescriptorExtractorIndex(String name){
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

    private void resetInitialMicroscopeCondition(Rectangle oldROI, Configuration oldState, double oldExposure, boolean oldAutoShutterState) throws Exception {
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
    }

    private String getLabelOfPositions(PositionList positionList) {
        if (positionIndex == positionList.getNumberOfPositions() ) {
            positionIndex = 0;
            timepoint++;
        }
        return positionList.getPosition(positionIndex).getLabel();
    }

    //XYZ-Methods
    private double[] getXYZPosition(String label) {
        return oldPositionsDict.get(label);
    }

    private void refreshOldXYZposition(double correctedXPosition, double correctedYPosition, double correctedZPosition, String label) {
        double[] refreshedXYZposition = new double[3];
        refreshedXYZposition[0] = correctedXPosition;
        refreshedXYZposition[1] = correctedYPosition;
        refreshedXYZposition[2] = correctedZPosition;
        oldPositionsDict.replace(label, refreshedXYZposition);
    }

    private void setToLastCorrectedPosition(double oldX, double oldY, double oldZ) throws Exception {
        setXYPosition(oldX, oldY);
        setZPosition(oldZ);
    }

    //Z-Methods
    private double getZPosition() throws Exception {
        String focusDevice = core_.getFocusDevice();
        return core_.getPosition(focusDevice);
    }

    private static int getZfocus (double[] stdArray){
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

    private void showImage(TaggedImage currentImg) {
        SwingUtilities.invokeLater(() -> {
            try {
                studio_.live().displayImage(studio_.data().convertTaggedImage(currentImg));
            }
            catch (JSONException | IllegalArgumentException e) {
                studio_.logs().showError(e);
            }
        });
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

    private static double optimizeZFocus(int rawZidx, double[] stdArray, double[] zpositionArray){
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

    private double calculateZFocus(double oldZ, String positionLabel, int timepoint, boolean save) throws Exception {
        double[] zpositions = calculateZPositions(searchRange, step, oldZ);
        double[] stdAtZPositions = new double[zpositions.length];
        TaggedImage currentImg;
        Datastore store = null;
        if (save){
            store = studio_.data().createMultipageTIFFDatastore(
                    savingPath + File.separator + positionLabel + "_T" + String.valueOf(timepoint),
                    false,false);
        }

        for (int i =0; i< zpositions.length ;i++){
            setZPosition(zpositions[i]);
            core_.waitForDevice(core_.getCameraDevice());
            core_.snapImage();
            currentImg = core_.getTaggedImage();
            imageCount++;
            Coords.CoordsBuilder builder = studio_.data().getCoordsBuilder().z(i).channel(0).stagePosition(0).time(timepoint);
            Image img = studio_.data().convertTaggedImage(currentImg, builder.build(), null);
            if (save){
                assert store != null;
                store.putImage(img);
            }
            stdAtZPositions[i] = studio_.data().ij().createProcessor(img).getStatistics().stdDev;
            if (show.contentEquals("Yes")) {
                showImage(currentImg);
            }
        }

        if (save) {
            store.freeze();
            store.close();
            studio_.core().clearCircularBuffer();
        }

        int rawIndex = getZfocus(stdAtZPositions);
        return optimizeZFocus(rawIndex, stdAtZPositions, zpositions);
    }

    private void setZPosition(double z) throws Exception {
        String focusDevice = core_.getFocusDevice();
        core_.setPosition(focusDevice, z);
        core_.waitForDevice(focusDevice);
    }

    //XY-Methods
    private double[] calculateXYDrifts(Mat currentImgMat, Integer detectorAlgo,
                                       Integer descriptorExtractor, Integer descriptorMatcher,
                                       Rectangle oldROI, Configuration oldState,
                                       double oldExposure, boolean oldAutoShutterState, int flag) {
        ExecutorService es = Executors.newSingleThreadExecutor();
        Future job = es.submit(new ThreadAttribution(imgRef_Mat, currentImgMat, calibration,
                intervalInMin, umPerStep, detectorAlgo, descriptorExtractor, descriptorMatcher, flag));
        double[] xyDrifts = new double[0];
        try {
            xyDrifts = (double[]) job.get();
        } catch (InterruptedException | ExecutionException e) {
            try {
                resetInitialMicroscopeCondition(oldROI, oldState, oldExposure, oldAutoShutterState);
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        }
        es.shutdown();
        try {
            es.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return xyDrifts;
    }

    private void setXYPosition(double x, double y) throws Exception {
        assert x != 0;
        assert y != 0;
        String xyDevice = core_.getXYStageDevice();
        core_.setXYPosition(x,y);
        core_.waitForDevice(xyDevice);
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
        Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(mat16);
        double min = minMaxResult.minVal;
        double max = minMaxResult.maxVal;
        mat16.convertTo(mat8, CvType.CV_8UC1, 255/(max-min));
        return DriftCorrection.equalizeImages(mat8);
    }

    //Write output file
    private void writeOutput(long acquisitionDuration, String label, String prefix, double currentXPosition, double correctedXPosition,
                             double currentYPosition, double correctedYPosition,
                             double currentZPosition, double correctedZPosition, double[] xyDrifts, double intervalInMin_) throws IOException {

        File f1 = new File(savingPath + prefix + "_Stats_" + label + ".csv");
        if (!f1.exists()) {
            boolean wrote = f1.createNewFile();
            if (wrote) {
                FileWriter fw = new FileWriter(f1);
                String[] headersOfFile = new String[]{"currentXPosition", "correctedXPosition",
                        "currentYPosition", "correctedYPosition",

                        "currentZPosition", "correctedZPosition",

                        "meanXdisplacement", "meanYdisplacement",

                        "medianXdisplacement", "medianYdisplacement",

                        "minXdisplacement", "minYdisplacement",

                        "modeXdisplacement", "modeYdisplacement",

                        "numberOfMatches", "numberOfGoodMatches",

                        "algorithmDuration(ms)", "acquisitionDuration(ms)", "intervalInMin"

                };

                fw.write(String.join(",", headersOfFile) + System.lineSeparator());
                fw.close();
            }else{
                ReportingUtils.logError("Can not create new file");
            }

        } else {
            double meanXdisplacement = xyDrifts[0];
            double meanYdisplacement = xyDrifts[1];
            double numberOfMatches = xyDrifts[2];
            double numberOfGoodMatches = xyDrifts[3];
            double algorithmDuration = xyDrifts[4];
            double medianXDisplacement = xyDrifts[0];
            double medianYDisplacement = xyDrifts[1];
            double minXDisplacement = xyDrifts[0];
            double minYDisplacement = xyDrifts[1];
            double modeXDisplacement = xyDrifts[0];
            double modeYDisplacement = xyDrifts[1];

            FileWriter fw1 = new FileWriter(f1, true);
            fw1.write(currentXPosition + "," + correctedXPosition + ","

                    + currentYPosition + "," + correctedYPosition + ","

                    + currentZPosition + "," + correctedZPosition  + ","

                    + meanXdisplacement + "," + meanYdisplacement + ","

                    + medianXDisplacement + "," + medianYDisplacement + ","

                    + minXDisplacement + "," + minYDisplacement + ","

                    + modeXDisplacement + "," + modeYDisplacement + ","

                    + numberOfMatches + "," + numberOfGoodMatches + ","

                    + algorithmDuration + "," + acquisitionDuration + "," + intervalInMin_

                    + System.lineSeparator());
            fw1.close();
        }
    }

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
    private class ThreadAttribution implements Callable<double[]> {

        private Mat img1_;
        private Mat img2_;
        private double calibration_;
        private double intervalInMs_;
        private double umPerStep_;
        private Integer detectorAlgo_;
        private Integer descriptorExtractor_;
        private Integer descriptorMatcher_;
        private int flag;

        ThreadAttribution(Mat img1, Mat img2, double calibration, double intervalInMs, double umPerStep,
                          Integer detectorAlgo, Integer descriptorExtractor, Integer descriptorMatcher, int flag) {
            img1_ = img1;
            img2_ = img2;
            calibration_ = calibration;
            intervalInMs_ = intervalInMs;
            umPerStep_ = umPerStep;
            detectorAlgo_ = detectorAlgo;
            descriptorExtractor_ = descriptorExtractor;
            descriptorMatcher_ = descriptorMatcher;
            this.flag = flag;
        }

        @Override
        public double[] call() {
            return DriftCorrection.driftCorrection(img1_, img2_, calibration_, intervalInMs_,
                    umPerStep_, detectorAlgo_, descriptorExtractor_, descriptorMatcher_, flag);
        }
    }
}

