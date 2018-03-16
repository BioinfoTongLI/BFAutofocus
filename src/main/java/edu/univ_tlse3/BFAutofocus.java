package edu.univ_tlse3;

import ij.IJ;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import mmcorej.CMMCore;
import mmcorej.Configuration;
import mmcorej.StrVector;
import mmcorej.TaggedImage;
import org.json.JSONException;
import org.micromanager.AutofocusPlugin;
import org.micromanager.MultiStagePosition;
import org.micromanager.PositionList;
import org.micromanager.Studio;
import org.micromanager.data.Coords;
import org.micromanager.data.Datastore;
import org.micromanager.data.Image;
import org.micromanager.internal.utils.*;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.SciJavaPlugin;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.ParseException;
import java.util.*;
import java.util.List;
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
    private Map positionDict = null;

    final static double alpha = 0.00390625;
    private int positionIndex = 0;
    private double oldX = Double.NaN;
    private double oldY = Double.NaN;
    private double oldZ = Double.NaN;

    public BFAutofocus() {
        super.createProperty(SEARCH_RANGE, NumberUtils.doubleToDisplayString(searchRange));
        super.createProperty(CROP_FACTOR, NumberUtils.doubleToDisplayString(cropFactor));
        super.createProperty(EXPOSURE, NumberUtils.doubleToDisplayString(exposure));
        super.createProperty(SHOW_IMAGES, show, SHOWVALUES);
        super.createProperty(INCREMENTAL, incremental, INCREMENTAL_VALUES);
        super.createProperty(XY_CORRECTION_TEXT, xy_correction, XY_CORRECTION);
        super.createProperty(STEP_SIZE, NumberUtils.doubleToDisplayString(step));
        super.createProperty(CHANNEL, channel);
        super.createProperty(PATH_REFIMAGE, pathOfReferenceImage);
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
            pathOfReferenceImage = getPropertyValue(PATH_REFIMAGE);
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

        setToLastCorrectedPosition();

        double correctedZPosition = zDriftCorrection();


        //Initialization of positions dictionary
        if (positionDict == null){
            positionDict = new HashMap<String, Mat>();
        }

        //Get an image to define reference image, for each position
        core.snapImage();
        TaggedImage imagePosition = core.getTaggedImage();

        PositionList positionList = studio_.positions().getPositionList();

        if (positionIndex == positionList.getNumberOfPositions() ) {
            positionIndex = 0;
        }
        String label = positionList.getPosition(positionIndex).getLabel();
        System.out.println("Label Position : " + label);

        //Incrementation of counter; does not work at another place
        positionIndex += 1;

//        System.out.println("Positions dictionary : " + positionDict.toString());

        //Previous method to define reference image; useful when simulating; when deleting it, also delete lines 51, 63, 78 and 94;
//        if (!pathOfReferenceImage.equals("")) {
//            ReportingUtils.logMessage("Loading reference image :" + pathOfReferenceImage);
//            imgRef_Mat = DriftCorrection.readImage(pathOfReferenceImage);
//        }else{
//            imgRef_Mat= toMat(IJ.getImage().getProcessor().convertToShortProcessor());
//        }



        //Initialization of parameters required for the stack
        int nThread = Runtime.getRuntime().availableProcessors() - 1;
        ExecutorService es = Executors.newFixedThreadPool(nThread);

        double oldZ = core.getPosition(core.getFocusDevice());

        double[] zpositions = calculateZPositions(searchRange, step, oldZ);
        double correctedXPosition = core.getXPosition();
        double correctedYPosition = core.getYPosition();
        String focusDevice = core.getFocusDevice();
        double z = core.getPosition(focusDevice);

        //Set shutter parameters for acquisition
        boolean oldAutoShutterState = core.getAutoShutter();
        core.setAutoShutter(false);
        core.setShutterOpen(true);

        double[] xyDrifts = calculateXYDrifts();

        //Define current image as reference for the position if it does not exist
        if (!positionDict.containsKey(label)) {
            Mat mat16Pos = convertToMat(imagePosition);
            Mat mat8Pos = new Mat(mat16Pos.cols(), mat16Pos.rows(), CvType.CV_8UC1);
            mat16Pos.convertTo(mat8Pos, CvType.CV_8UC1, alpha);
            Mat mat8PosSet = DriftCorrection.equalizeImages(mat8Pos);
            positionDict.put(label, mat8PosSet);
        } else {
            imgRef_Mat = (Mat) positionDict.get(label);

            double[] driftsCorrection = runAutofocusAlgorithm(zpositions, es, core);
            double xCorrection = driftsCorrection[0];
            double yCorrection = driftsCorrection[1];
            z = driftsCorrection[2];

            correctedXPosition = core.getXPosition() - xCorrection;
            correctedYPosition = core.getYPosition() - yCorrection;

            System.out.println("X Correction : " + xCorrection);
            System.out.println("Y Correction : " + yCorrection);
            System.out.println("absolute Z : " + z);
        }

        //Reinitialize origin ROI and all others parameters
        core.setAutoShutter(oldAutoShutterState);

        if (cropFactor < 1.0) {
            studio_.app().setROI(oldROI);
            core.waitForDevice(core.getCameraDevice());
        }

        if (oldState != null) {
            core.setSystemState(oldState);
        }
        core.setExposure(oldExposure);

        System.out.println("Xcorrected : " + correctedXPosition);
        System.out.println("Ycorrected : " + correctedYPosition);

        //Set X,Y and Z corrected values
        setZPosition(z);
        if (xy_correction.contentEquals("Yes")){
            xyDrifts
            setXYPosition(correctedXPosition, correctedYPosition);
        }

        refreshOldXYZposition(correctedXPosition, correctedYPosition, correctedZPosition);

        //Set autofocus incremental
        if (Boolean.parseBoolean(incremental)){
            core.waitForDevice(core.getCameraDevice());
            core.snapImage();
            final TaggedImage focusImg = core.getTaggedImage();
            Mat mat16Incremental = convertToMat(focusImg);
            Mat mat8Incremental = new Mat(mat16Incremental.cols(), mat16Incremental.rows(), CvType.CV_8UC1);
            mat16Incremental.convertTo(mat8Incremental, CvType.CV_8UC1, alpha);
            Mat mat8SetIncremental = DriftCorrection.equalizeImages(mat8Incremental);
            imgRef_Mat = mat8SetIncremental;
        }

//        writeOutput(startTime, drifts, xCorrection, yCorrection, correctedXPosition, correctedYPosition, z);
        long endTime = new Date().getTime();
        long timeElapsed = endTime - startTime;
        System.out.println("Time Elapsed : " + timeElapsed);
        return z;
    }

    private double[] runAutofocusAlgorithm( ExecutorService es, CMMCore core) throws Exception {
        double[] stdAtZPositions = new double[zpositions.length];
        Future[] jobs = new Future[zpositions.length];
        double[] goodMatchNumberArray = new double[zpositions.length];
        Mat mat16;
        Mat mat8;
        Mat mat8Set;

        for (int i = 0; i < zpositions.length ;i++) {
            setZPosition(zpositions[i]);
            core.waitForDevice(core.getCameraDevice());
            core.snapImage();
            final TaggedImage currentImg = core.getTaggedImage();

            mat16 = convertToMat(currentImg);
            mat8 = new Mat(mat16.cols(), mat16.rows(), CvType.CV_8UC1);
            mat16.convertTo(mat8, CvType.CV_8UC1, alpha);
            mat8Set = DriftCorrection.equalizeImages(mat8);
            //uncomment next line before simulation:
            //mat8Set = DriftCorrection.readImage("/home/dataNolwenn/Résultats/06-03-2018/ImagesFocus/19-5.tif");
            imageCount_++;
            jobs[i] = es.submit(new ThreadAttribution(imgRef_Mat, mat8Set));

            Image img = studio_.data().convertTaggedImage(currentImg);
            stdAtZPositions[i] = studio_.data().ij().createProcessor(img).getStatistics().stdDev;

            showImage(currentImg);
        }

        //Get results of each slice
        List<double[]> drifts = new ArrayList<>();
        try {
            for (int i = 0; i < jobs.length ;i++) {
                drifts.add(i, (double[]) jobs[i].get());
                goodMatchNumberArray[i] = drifts.get(i)[3];
            }
        } catch (InterruptedException | ExecutionException e) {
            ReportingUtils.logError("Can not calculate drifts");
        }
        es.shutdown();
        try {
            es.awaitTermination(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

//        //Calcul of focus by best distance
//        double[] distances = calculateDistances(drifts);
//        int indexOfMinDistance = getIndexOfBestDistance(distances);
//        double[] bestDistance = drifts.get(indexOfMinDistance);
//        double xCorrection = bestDistance[0];
//        double yCorrection = bestDistance[1];
//        double numberOfGoodMatchesBestDist = bestDistance[3];
//        System.out.println("Good Matches of best distance : " + numberOfGoodMatchesBestDist);
//
//        //Calcul of focus by number of Good Matches
//        int indexOfMaxNumberGoodMatches = getIndexOfMaxNumberGoodMatches(goodMatchNumberArray);
//        double[] maxNumberGoodMatches = drifts.get(indexOfMaxNumberGoodMatches);
//        double zMaxNumberGoodMatches = zpositions[indexOfMaxNumberGoodMatches];
//
//        //Calcul of focus by StdDev
//        int indexFocusStdDev = getMinZfocus(stdAtZPositions);
//        double[] stdDevFocus = drifts.get(indexFocusStdDev);
//        double zOptimizedStdDev = optimizeZFocus(indexFocusStdDev, stdAtZPositions, zpositions);

        //Calcul of focus by SD and number of good matches
        int indexOfBestFocus = getindexOfBestFocus(stdAtZPositions, zpositions, goodMatchNumberArray, drifts);

        //Get X and Y Correction according to best focus position in arrays
        double[] bestFocus = drifts.get(indexOfBestFocus);
        double deltaX = bestFocus[0];
        double deltaY = bestFocus[1];
        double z = zpositions[indexOfBestFocus];
        System.out.println("absolute Z position : " + indexOfBestFocus);

        double[] driftsCorrection = new double[3];
        driftsCorrection[0] = deltaX;
        driftsCorrection[1] = deltaY;
        driftsCorrection[2] = z;

        return driftsCorrection;
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
        int indexOfBestFocus = (indexOfMaxNumberGoodMatches + indexFocusStdDev) / 2;

        return  indexOfBestFocus;
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

    //Convert MM Short Processor to OpenCV Mat
    private static Mat toMat(ShortProcessor sp) {
        final int w = sp.getWidth();
        final int h = sp.getHeight();
        Mat mat = new Mat(h, w, CvType.CV_16UC1);
        mat.put(0,0, (short[]) sp.getPixels());
        Mat res = new Mat(h, w, CvType.CV_8UC1);
        mat.convertTo(res, CvType.CV_8UC1, alpha);
        Mat resSet = DriftCorrection.equalizeImages(res);
        return resSet;
    }

    private void writeOutput(long startTime, List<double[]> drifts, double xCorrection, double yCorrection, double correctedXPosition, double correctedYPosition, double z) throws IOException {
        double[] listOfVariances = getVariances(drifts);
        double xVariance = listOfVariances[0];
        double yVariance = listOfVariances[1];

        System.out.println("\nCorrected X Position : " + correctedXPosition);
        System.out.println("Corrected Y Position : " + correctedYPosition);
        System.out.println("Z Best Position : " + z);

        long endTime = new Date().getTime();
        long timeElapsed = endTime - startTime;
        System.out.println("\nDuration in milliseconds : " + timeElapsed);

        System.out.println("X Variance : " + xVariance);
        System.out.println("Y Variance : " + yVariance);

        //For "statistics" tests
        File f = new File("/home/dataNolwenn/ImagesTest/Stats.csv");
        FileWriter fw = new FileWriter(f, true);

        for (int i = 0; i < drifts.size(); i++) {
            double[] driftArray = drifts.get(i);
            float xDrift = (float) driftArray[0];
            float yDrift = (float) driftArray[1];
            int numberMatches = (int) driftArray[2];
            int numberGoodMatches = (int) driftArray[3];
            fw.write((i+1) + "," + numberMatches + "," + numberGoodMatches + "," + xDrift + "," + yDrift + "\n");
        }
        fw.write("**,*****,***,***********,**********\n");
        fw.close();

        File f1 = new File("/home/dataNolwenn/ImagesTest/StatsTot.csv");
        FileWriter fw1 = new FileWriter(f1, true);
        fw1.write(xVariance + "," + yVariance + "," + xCorrection + "," + yCorrection + ","
                + correctedXPosition + "," + correctedYPosition + "," + z + "," + timeElapsed + "\n");
        fw1.close();
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

