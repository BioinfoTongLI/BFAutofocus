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
import org.micromanager.Studio;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;

@Plugin(type = AutofocusPlugin.class)
public class BFAutofocus extends AutofocusBase implements AutofocusPlugin, SciJavaPlugin {
    private static final String VERSION_INFO = "0.0.1";
    private static final String NAME = "Bright-field autofocus";
    private static final String HELPTEXT = "This simple autofocus is only designed to process transmitted-light (or DIC) images, Z-stack is required.";
    private static final String COPYRIGHT_NOTICE = "CeCILL-B-BSD compatible";
    private Studio studio_;
    private Mat imgRef_Mat;

    private static final String SEARCH_RANGE = "SearchRange_um";
    private static final String CROP_FACTOR = "CropFactor";
    private static final String CHANNEL = "Channel";
    private static final String EXPOSURE = "Exposure";
    private static final String SHOW_IMAGES = "ShowImages";
    private static final String XY_CORRECTION_TEXT = "Correcte XY at same time";
    private static final String[] SHOWVALUES = {"Yes", "No"};
    private static final String STEP_SIZE = "Step_size";
    private static final String PATH_REFIMAGE = "Path of reference image";
    private static final String[] XY_CORRECTION = {"Yes", "No"};

    private double searchRange = 10;
    private double cropFactor = 1;
    private String channel = "";
    private double exposure = 10;
    private String show = "Yes";
    private int imageCount_;
    private double step = 0.3;
    private String pathOfReferenceImage = "";
    private String xy_correction = "Yes";

    final static double alpha = 0.00390625;

    public BFAutofocus() {
        super.createProperty(SEARCH_RANGE, NumberUtils.doubleToDisplayString(searchRange));
        super.createProperty(CROP_FACTOR, NumberUtils.doubleToDisplayString(cropFactor));
        super.createProperty(EXPOSURE, NumberUtils.doubleToDisplayString(exposure));
        super.createProperty(SHOW_IMAGES, show, SHOWVALUES);
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
//        if (!pathOfReferenceImage.equals("")){
//            ReportingUtils.logMessage("Loading reference image :" + pathOfReferenceImage);
//            imgRef_Mat = DriftCorrection.readImage(pathOfReferenceImage);
//        }else{
//            imgRef_Mat= toMat(IJ.getImage().getProcessor().convertToShortProcessor());
//        }
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

        // avoid wasting time on setting roi if it is the same
        if (cropFactor < 1.0) {
            studio_.app().setROI(newROI);
            core.waitForDevice(core.getCameraDevice());
        }
        double oldExposure = core.getExposure();
        core.setExposure(exposure);

        if (cropFactor < 1.0) {
            studio_.app().setROI(oldROI);
            core.waitForDevice(core.getCameraDevice());
        }

        int nThread = Runtime.getRuntime().availableProcessors() - 1;
        ExecutorService es = Executors.newFixedThreadPool(nThread);

        double oldZ = core.getPosition(core.getFocusDevice());
        double[] zpositions = calculateZPositions(searchRange, step, oldZ);
        Future[] jobs = new Future[zpositions.length];

        boolean oldAutoShutterState = core.getAutoShutter();
        core.setAutoShutter(false);
        core.setShutterOpen(true);

        Mat mat16;
        Mat mat8;
        Mat mat8Set;
        for (int i = 0; i < zpositions.length ;i++) {
            setZPosition(zpositions[i]);
            core.waitForDevice(core.getCameraDevice());
            core.snapImage();
            final TaggedImage currentImg = core.getTaggedImage();
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
            mat16 = convert(currentImg);
            mat8 = new Mat(mat16.cols(), mat16.rows(), CvType.CV_8UC1);
            mat16.convertTo(mat8, CvType.CV_8UC1, alpha);
            mat8Set = DriftCorrection.equalizeImages(mat8);
//            mat8Set = DriftCorrection.readImage("/home/dataNolwenn/ImagesTest/2-38.tif");
            imageCount_++;
            jobs[i] = es.submit(new ThreadAttribution(imgRef_Mat, mat8Set));
            imgRef_Mat = mat8Set;
        }
        core.setAutoShutter(oldAutoShutterState);

        List<double[]> drifts = new ArrayList<>();
        try {
            for (int i = 0; i < jobs.length ;i++) {
                drifts.add(i, (double[]) jobs[i].get());
            }
        } catch (InterruptedException | ExecutionException e) {
            ReportingUtils.logError("Can not calculate drifts");
        }
        es.shutdown();
        try {
            es.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        double[] distances = calculateDistances(drifts);
        int indexOfMinDistance = getIndexOfBestDistance(distances);
        double[] bestDistance = drifts.get(indexOfMinDistance);
        System.out.println("index of min dist : " + indexOfMinDistance);
        System.out.println("size drifts array : " + drifts.size());
        System.out.println("bestDistances array : " + Arrays.toString(bestDistance));

        double xCorrection = bestDistance[0];
        double yCorrection = bestDistance[1];

        System.out.println("X Correction : " + xCorrection);
        System.out.println("Y Correction : " + yCorrection);

        double correctedXPosition = core.getXPosition() - xCorrection;
        double correctedYPosition = core.getYPosition() - yCorrection;
        double z = zpositions[indexOfMinDistance];

        System.out.println("absolute Z : " + z);
        System.out.println("absolute Z position : " + indexOfMinDistance);

        if (oldState != null) {
            core.setSystemState(oldState);
        }
        core.setExposure(oldExposure);

        setZPosition(z);
        if (xy_correction.contentEquals("Yes")){
            setXYPosition(correctedXPosition, correctedYPosition);
        }

//        writeOutput(startTime, drifts, xCorrection, yCorrection, correctedXPosition, correctedYPosition, z);

        return z;
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

    private static Mat convert(TaggedImage img) throws JSONException {
        int width = img.tags.getInt("Width");
        int height = img.tags.getInt("Height");
        Mat mat = new Mat(height, width, CvType.CV_16UC1);
        mat.put(0,0, (short[]) img.pix);
        return mat;
    }

    private static Mat toMat(ShortProcessor sp) {
        final int w = sp.getWidth();
        final int h = sp.getHeight();
        Mat mat = new Mat(h, w, CvType.CV_16UC1);
        mat.put(0,0, (short[]) sp.getPixels());
        Mat res = new Mat(h, w, CvType.CV_8UC1);
        mat.convertTo(res, CvType.CV_8UC1, BFAutofocus.alpha);
        Mat resSet = DriftCorrection.equalizeImages(res);
        return resSet;
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

