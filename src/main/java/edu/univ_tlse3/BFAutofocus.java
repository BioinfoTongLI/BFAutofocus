package edu.univ_tlse3;

import ij.process.ImageProcessor;
import mmcorej.CMMCore;
import mmcorej.Configuration;
import mmcorej.TaggedImage;
import org.json.JSONException;
import org.micromanager.AutofocusPlugin;
import org.micromanager.Studio;
import org.micromanager.internal.utils.*;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.SciJavaPlugin;

import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;

@Plugin(type = AutofocusPlugin.class)
public class BFAutofocus extends AutofocusBase implements AutofocusPlugin, SciJavaPlugin {
    private static final String VERSION_INFO = "1.0.0";
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
    private static final String[] SHOWVALUES = {"Yes", "No"};
    private static final String STEP_SIZE = "Step_size";
    private static final String PATH_REFIMAGE = "Path of reference image";

    private double searchRange = 19;
    private double cropFactor = 1;
    private String channel = "DAPI";
    private double exposure = 10;
    private String show = "Yes";
    private int imageCount_;
    private double step = 0.3;
    private String pathOfReferenceImage = null;

    public BFAutofocus() {
        super.createProperty(SEARCH_RANGE, NumberUtils.doubleToDisplayString(searchRange));
        super.createProperty(CROP_FACTOR, NumberUtils.doubleToDisplayString(cropFactor));
        super.createProperty(EXPOSURE, NumberUtils.doubleToDisplayString(exposure));
        super.createProperty(SHOW_IMAGES, show, SHOWVALUES);
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
            pathOfReferenceImage = getPropertyValue(PATH_REFIMAGE);
        } catch (MMException ex) {
            studio_.logs().logError(ex);
        } catch (ParseException ex) {
            studio_.logs().logError(ex);
        }
    }

    @Override
    public double fullFocus() throws Exception {
        long startTime = new Date().getTime();

        applySettings();
        Rectangle oldROI = studio_.core().getROI();
        CMMCore core = studio_.getCMMCore();
        imgRef_Mat = DriftCorrection.readImage("/home/nolwenngueguen/Téléchargements/ImagesTest/1-36.tif");

//        DriftCorrection.displayImageIJ("Ref Image ", imgRef_Mat);

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
        if (oldState != null) {
            core.setSystemState(oldState);
        }
        core.setExposure(oldExposure);

        int nThread = Runtime.getRuntime().availableProcessors() - 1;
        ExecutorService es = Executors.newFixedThreadPool(nThread);

        double oldZ = core.getPosition(core.getFocusDevice());
        double[] zpositions = calculateZPositions(searchRange, step, oldZ);
        Future[] jobs = new Future[zpositions.length];
        TaggedImage currentImg;

        for (int i = 0; i < zpositions.length ;i++) {
//            String path = "/home/nolwenngueguen/Téléchargements/ImagesTest/2-" + (i+1) + ".tif";
//            Mat imgCurrent_Mat = DriftCorrection.readImage(path);
//            DriftCorrection.displayImageIJ("Image 2-" + (i+1), imgCurrent_Mat);

            setZPosition(zpositions[i]);
            core.waitForDevice(core.getCameraDevice());
            core.snapImage();
            currentImg = core.getTaggedImage();
            Mat mat = convert(currentImg);
//            DriftCorrection.displayImageIJ("Image 2-" + (i+1), convert(currentImg));
            imageCount_++;
//            Image img = studio_.data().convertTaggedImage(currentImg);
//            jobs[i] = es.submit(new ThreadAttribution(imgRef_Mat, imgCurrent_Mat));
        }

        List<double[]> drifts = new ArrayList<double[]>();
        try {
            for (int i = 0; i < jobs.length ;i++) {
                drifts.add(i, (double[]) jobs[i].get());
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
        double[] distances = calculateDistances(drifts);
        int indexOfMinDistance = getIndexOfBestDistance(distances);
        double[] bestDistance = drifts.get(indexOfMinDistance);

        double xCorrection = bestDistance[0];
        double yCorrection = bestDistance[1];

        System.out.println("X Correction : " + xCorrection);
        System.out.println("Y Correction : " + yCorrection);

        double correctedXPosition = core.getXPosition() - xCorrection;
        double correctedYPosition = core.getYPosition() - yCorrection;
        double z = zpositions[indexOfMinDistance];

        setZPosition(z);
        setXYPosition(correctedXPosition, correctedYPosition);
//        core.snapImage();

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
        File f = new File("/home/nolwenngueguen/Téléchargements/ImagesTest/Stats.csv");
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

        File f1 = new File("/home/nolwenngueguen/Téléchargements/ImagesTest/StatsTot.csv");
        FileWriter fw1 = new FileWriter(f1, true);
        fw1.write(xVariance + "," + yVariance + "," + xCorrection + "," + yCorrection + ","
                + correctedXPosition + "," + correctedYPosition + "," + z + "," + timeElapsed + "\n");
        fw1.close();

        return z;
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

    public static Mat convert(TaggedImage img) throws JSONException {
        int width = img.tags.getInt("Width");
        int height = img.tags.getInt("Height");
        Mat mat = new Mat(height, width, CvType.CV_16UC1);
        mat.put(0,0, (short[]) img.pix);
        return mat;
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

