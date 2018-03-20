package edu.univ_tlse3;

import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import mmcorej.CMMCore;
import mmcorej.Configuration;
import mmcorej.StrVector;
import mmcorej.TaggedImage;
import org.json.JSONException;
import org.micromanager.AutofocusPlugin;
import org.micromanager.PositionList;
import org.micromanager.Studio;
import org.micromanager.data.Datastore;
import org.micromanager.data.Image;
import org.micromanager.internal.utils.*;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
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
    private static final String VERSION_INFO = "0.0.1";
    private static final String NAME = "Bright-field autofocus";
    private static final String HELPTEXT = "This simple autofocus is only designed to process transmitted-light (or DIC) images, Z-stack is required.";
    private static final String COPYRIGHT_NOTICE = "CeCILL-B-BSD compatible";
    private Studio studio_;
    private Mat imgRef_Mat = null;

    private static final String SEARCH_RANGE = "SearchRange_um";
    private static final String CROP_FACTOR = "CropFactor";
    private static final String CHANNEL = "Channel";
    private static final String EXPOSURE = "Exposure";
    private static final String SHOW_IMAGES = "ShowImages";
    private static final String INCREMENTAL = "incremental";
    private static final String XY_CORRECTION_TEXT = "Correct XY at same time";
    private static final String[] SHOWVALUES = {"Yes", "No"};
    private static final String STEP_SIZE = "Step_size";
    private static final String PATH_REFIMAGE = "Path of reference image";
    private static final String[] XY_CORRECTION = {"Yes", "No"};
    private static final String[] INCREMENTAL_VALUES = {"Yes", "No"};

    private double searchRange = 10;
    private double cropFactor = 1;
    private String channel = "BF";
    private double exposure = 10;
    private String show = "No";
    private String incremental = "No";
    private int imageCount_;
    private double step = 0.3;
    private String pathOfReferenceImage = "";
    private String xy_correction = "Yes";
    private Map refImageDict = null;
    private Map oldPositionsDict = null;

    final static double alpha = 0.00390625;
    private int positionIndex = 0;

    private PrintStream psError;
    private PrintStream psOutput;
    private PrintStream curr_err;
    private PrintStream curr_out;
    private String savingPath = "D:\\DATA\\Nolwenn\\20-03-2018_AF_Brisk\\";
    private Datastore storeNonCorrectedImages = null;

    public BFAutofocus() {
        super.createProperty(SEARCH_RANGE, NumberUtils.doubleToDisplayString(searchRange));
        super.createProperty(CROP_FACTOR, NumberUtils.doubleToDisplayString(cropFactor));
        super.createProperty(EXPOSURE, NumberUtils.doubleToDisplayString(exposure));
        super.createProperty(SHOW_IMAGES, show, SHOWVALUES);
        super.createProperty(INCREMENTAL, incremental, INCREMENTAL_VALUES);
        super.createProperty(XY_CORRECTION_TEXT, xy_correction, XY_CORRECTION);
        super.createProperty(STEP_SIZE, NumberUtils.doubleToDisplayString(step));
        super.createProperty(CHANNEL, channel);
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
        CMMCore core = studio_.getCMMCore();

        //ReportingUtils.logMessage("Original ROI: " + oldROI);
        int w = (int) (oldROI.width * cropFactor);
        int h = (int) (oldROI.height * cropFactor);
        int x = oldROI.x + (oldROI.width - w) / 2;
        int y = oldROI.y + (oldROI.height - h) / 2;
        Rectangle newROI = new Rectangle(x, y, w, h);

        //ReportingUtils.logMessage("Setting ROI to: " + newROI);
        Configuration oldState = null;
        if (channel.length() > 0) {
            String chanGroup = core.getChannelGroup();
            oldState = core.getConfigGroupState(chanGroup);
            core.setConfig(chanGroup, channel);
        }

        //Avoid wasting time on setting roi if it is the same
        if (cropFactor < 1.0) {
            studio_.app().setROI(newROI);
            core.waitForDevice(core.getCameraDevice());
        }

        double oldExposure = core.getExposure();
        core.setExposure(exposure);

        try {
            psError = new PrintStream(savingPath + File.separator + "Error.LOG");
            psOutput = new PrintStream(savingPath + File.separator + "Output.LOG");
            curr_err = System.err;
            curr_out = System.out;
            System.setOut(psOutput);
            System.setErr(psError);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        //Get label of position
        PositionList positionList = studio_.positions().getPositionList();
        String label = getLabelOfPositions(positionList);
        System.out.println("Label Position : " + label);

        //Initialization of reference Images and old positions dictionaries
        if (refImageDict == null){
            refImageDict = new HashMap<String, Mat>();
        }

        if (oldPositionsDict == null){
            oldPositionsDict = new HashMap<String, double[]>();
        }

        //Initialization of Datastore, to store non corrected images
        if (storeNonCorrectedImages == null) {
            storeNonCorrectedImages = studio_.data().createSinglePlaneTIFFSeriesDatastore(savingPath);
        }

        //Add current non-corrected image to the datastore
        core.snapImage();
        TaggedImage nonCorrectedTaggedImage = core.getTaggedImage();
        Image nonCorrectedImage = studio_.data().convertTaggedImage(nonCorrectedTaggedImage);
        storeNonCorrectedImages.putImage(nonCorrectedImage);

        System.out.println("DataStore Number Of Images : " + storeNonCorrectedImages.getNumImages());

        //Incrementation of counter; does not work at another place
        positionIndex += 1;

        //Define positions if it does not exist
        if (!oldPositionsDict.containsKey(label)) {
            double[] currentPositions = new double[3];
            currentPositions[0] = core.getXPosition();
            currentPositions[1] = core.getYPosition();
            String focusDevice = core.getFocusDevice();
            currentPositions[2] = core.getPosition(focusDevice);
            oldPositionsDict.put(label, currentPositions);
        }

        //Get X, Y and Z of a given position
        double[] xyzPosition = getXYZPosition(label);
        double oldX = xyzPosition[0];
        double oldY = xyzPosition[1];
        double oldZ = xyzPosition[2];

        //Set to the last good position calculated
        setToLastCorrectedPosition(core, xyzPosition, oldX, oldY, oldZ);
        System.out.println("old x : " + oldX);
        System.out.println("old y : " + oldY);
        System.out.println("old z : " + oldZ);

        //Calculate Focus
        double correctedZPosition = zDriftCorrection(core, oldZ);
        //Set to the focus
        setZPosition(correctedZPosition);

        //Get an image to define reference image, for each position
        core.snapImage();
        TaggedImage imagePosition = core.getTaggedImage();
        Mat currentMat8Set = convertTo8BitsMat(imagePosition);
        System.out.println("Position Index current TaggedImage : " + imagePosition.tags.getString("PositionIndex"));
        System.out.println("Frame Index current TaggedImage : " + imagePosition.tags.getString("FrameIndex"));
        System.out.println("Slice Index current TaggedImage : " + imagePosition.tags.getString("SliceIndex"));
        System.out.println("Time and Date current TaggedImage : " + imagePosition.tags.getString("Time"));

//        //Initialization of parameters required for the stack
//        int nThread = Runtime.getRuntime().availableProcessors() - 1;
//        ExecutorService es = Executors.newFixedThreadPool(nThread);

        double currentXPosition = core.getXPosition();
        double currentYPosition = core.getYPosition();

        double correctedXPosition = currentXPosition;
        double correctedYPosition = currentYPosition;

        double xCorrection = 0;
        double yCorrection = 0;
        double[] xyDrifts = new double[8];

        //Set shutter parameters for acquisition
        boolean oldAutoShutterState = core.getAutoShutter();
        core.setAutoShutter(false);
        core.setShutterOpen(true);

        //Define current image as reference for the position if it does not exist
        if (!refImageDict.containsKey(label)) {
            refImageDict.put(label, currentMat8Set);
        } else {
            imgRef_Mat = (Mat) refImageDict.get(label);
            xyDrifts = calculateXYDrifts(currentMat8Set);
            xCorrection = xyDrifts[0];
            yCorrection = xyDrifts[1];
            correctedXPosition = currentXPosition - xCorrection;
            correctedYPosition = currentYPosition - yCorrection;
            System.out.println("Xcorrected : " + correctedXPosition);
            System.out.println("Ycorrected : " + correctedYPosition);
            System.out.println("Zcorrected : " + correctedZPosition);
        }

        //Reinitialize origin ROI and all other parameters
        core.setAutoShutter(oldAutoShutterState);

        if (cropFactor < 1.0) {
            studio_.app().setROI(oldROI);
            core.waitForDevice(core.getCameraDevice());
        }

        if (oldState != null) {
            core.setSystemState(oldState);
        }
        core.setExposure(oldExposure);

        //Set X, Y and Z corrected values
        if (xy_correction.contentEquals("Yes")){
            setXYPosition(correctedXPosition, correctedYPosition);
        }

        //Refresh positions in position dictionary
        refreshOldXYZposition(correctedXPosition, correctedYPosition, correctedZPosition, label);

        //Set autofocus incremental
        if (Boolean.parseBoolean(incremental)){
            core.waitForDevice(core.getCameraDevice());
            core.snapImage();
            final TaggedImage focusImg = core.getTaggedImage();
            imgRef_Mat = convertTo8BitsMat(focusImg);
        }

        writeOutput(startTime, label, xCorrection, yCorrection, oldX, oldY, oldZ,
                correctedXPosition, correctedYPosition, correctedZPosition, xyDrifts);
        long endTime = new Date().getTime();
        long timeElapsed = endTime - startTime;
        System.out.println("Time Elapsed : " + timeElapsed);

        psError.close();
        psOutput.close();
        System.setOut(curr_out);
        System.setErr(curr_err);

        return correctedZPosition;
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

    private void setToLastCorrectedPosition(CMMCore core, double[] xyzPosition, double oldX, double oldY, double oldZ) throws Exception {
//        if (xyzPosition.length == 0) {
//            setXYPosition(core.getXPosition(), core.getYPosition());
//            String focusDevice = core.getFocusDevice();
//            double currentZ = core.getPosition(focusDevice);
//            setZPosition(currentZ);
//        } else {
        setXYPosition(oldX, oldY);
        setZPosition(oldZ);
//        }
    }

    private double zDriftCorrection(CMMCore core, double oldZ) throws Exception {
        double[] zpositions = calculateZPositions(searchRange, step, oldZ);
        double[] stdAtZPositions = new double[zpositions.length];
        TaggedImage currentImg;

        boolean oldAutoShutterState = core.getAutoShutter();
        core.setAutoShutter(false);
        core.setShutterOpen(true);

        for (int i =0; i< zpositions.length ;i++){
            setZPosition(zpositions[i]);
            core.waitForDevice(core.getCameraDevice());
            core.snapImage();
            currentImg = core.getTaggedImage();
            imageCount_++;
            Image img = studio_.data().convertTaggedImage(currentImg);
            stdAtZPositions[i] = studio_.data().ij().createProcessor(img).getStatistics().stdDev;
            if (show.contentEquals("Yes")) {
                showImage(currentImg);
            }
        }

        core.setAutoShutter(oldAutoShutterState);
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

    private double[] calculateXYDrifts(Mat currentImgMat) throws Exception {
        //uncomment next line before simulation:
        //currentImgMat = DriftCorrection.readImage("/home/dataNolwenn/Résultats/06-03-2018/ImagesFocus/19-5.tif");
        ExecutorService es = Executors.newSingleThreadExecutor();
        Future job = es.submit(new ThreadAttribution(imgRef_Mat, currentImgMat));
        double[] xyDrifts = (double[]) job.get();
        es.shutdown();
        try {
            es.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return xyDrifts;
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
        CMMCore core = studio_.getCMMCore();
        String xyDevice = core.getXYStageDevice();
        core.setXYPosition(x,y);
        core.waitForDevice(xyDevice);
    }

    private void setZPosition(double z) throws Exception {
        CMMCore core = studio_.getCMMCore();
        String focusDevice = core.getFocusDevice();
        core.setPosition(focusDevice, z);
        core.waitForDevice(focusDevice);
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
        mat16.convertTo(mat8, CvType.CV_8UC1, alpha);
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

    private void writeOutput(long startTime, String label, double xCorrection, double yCorrection, double oldX,
                             double oldY, double oldZ, double correctedXPosition, double correctedYPosition, double correctedZPosition,
                             double[] xyDrifts) throws IOException {
        long endTime = new Date().getTime();
        long timeElapsed = endTime - startTime;
        double meanXdisplacementBRISK = xyDrifts[0];
        double meanYdisplacementBRISK = xyDrifts[1];
        double numberOfMatchesBRISK = xyDrifts[2];
        double numberOfGoodMatchesBRISK = xyDrifts[3];
        double meanXdisplacementORB = xyDrifts[4];
        double meanYdisplacementORB = xyDrifts[5];
        double numberOfMatchesORB = xyDrifts[6];
        double numberOfGoodMatchesORB = xyDrifts[7];

        //For "statistics" tests
        File f1 = new File(savingPath + "Stats" + label + ".csv");
        if (!f1.exists()) {
            f1.createNewFile();
            FileWriter fw = new FileWriter(f1);
            fw.write("labelOfPosition" + "," + "xCorrection" + "," + "yCorrection" + "," + "oldX" + "," + "oldY" + "," + "oldZ" + ","
                    + "correctedXPosition" + "," + "correctedYPosition" + "," + "correctedZPosition" + "," + "timeElapsed" + ","
                    + "meanXdisplacementBRISK" + "," + "meanYdisplacementBRISK" + "," + "numberOfMatchesBRISK" + "," + "numberOfGoodMatchesBRISK" + ","
                    + "meanXdisplacementORB" + "," + "meanYdisplacementORB" + "," + "numberOfMatchesORB" + "," + "numberOfGoodMatchesORB" + "\n");
            fw.close();
        }

        FileWriter fw1 = new FileWriter(f1, true);
        fw1.write(label + "," + xCorrection + "," + yCorrection + "," + oldX + "," + oldY + "," + oldZ + ","
                + correctedXPosition + "," + correctedYPosition + "," + correctedZPosition + "," + timeElapsed + ","
                + meanXdisplacementBRISK + "," + meanYdisplacementBRISK + "," + numberOfMatchesBRISK + "," + numberOfGoodMatchesBRISK + ","
                + meanXdisplacementORB + "," + meanYdisplacementORB + "," + numberOfMatchesORB + "," + numberOfGoodMatchesORB + "\n");
        fw1.close();
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

        ThreadAttribution(Mat img1, Mat img2) {
            img1_ = img1;
            img2_ = img2;
        }

        @Override
        public double[] call() {
            return DriftCorrection.driftCorrection(img1_, img2_);
        }
    }


}

