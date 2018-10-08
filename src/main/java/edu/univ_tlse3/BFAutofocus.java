package edu.univ_tlse3;

import ij.IJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import mmcorej.*;
import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacpp.opencv_imgproc;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.json.JSONException;
import org.micromanager.AutofocusPlugin;
import org.micromanager.PositionList;
import org.micromanager.Studio;
import org.micromanager.data.Coords;
import org.micromanager.data.Datastore;
import org.micromanager.data.Image;
import org.micromanager.internal.utils.*;
import java.awt.image.BufferedImage;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.SciJavaPlugin;

import org.bytedeco.javacpp.opencv_core.*;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.FloatBuffer;
import java.text.ParseException;
import java.util.*;

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
    private static final String[] SHOWIMAGES_VALUES = {"Yes", "No"};
    private static final String[] SAVEIMAGES_VALUES = {"Yes", "No"};
    private static final String STEP_SIZE = "Step_size";
    private static final String[] XY_CORRECTION_VALUES = {"Yes", "No"};
    private static final String UMPERSTEP = "µm displacement allowed per time point";
    private static final String Z_OFFSET = "Z offset";

    //Set default parameters
    private double searchRange = 10;
    private double cropFactor = 1;
    private String channel = "BF";
    private double exposure = 50;
    private String show = "Yes";
    private String save = "Yes";
    private int imageCount = 0;
    private int timepoint = 0;
    private double step = 0.3;
    private String xy_correction = "Yes";
    private Map<String, TaggedImage> refImageDict = new HashMap<>();
    private Map<String, double[]> oldPositionsDict = new HashMap<>();
    private double umPerStep = 15;
    private double zOffset = -1;

    //Global variables
    private Studio studio_;
    private CMMCore core_;
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
        super.createProperty(STEP_SIZE, NumberUtils.doubleToDisplayString(step));
        super.createProperty(CHANNEL, channel);
        super.createProperty(UMPERSTEP, NumberUtils.doubleToDisplayString(umPerStep));
        super.createProperty(SAVEIMGS_TEXT, save, SAVEIMAGES_VALUES);
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
        TaggedImage currentImg = core_.getTaggedImage();

        //Calculation of XY Drifts only if the parameter "Correct XY at same time" is set to Yes;
        double currentXPosition = core_.getXPosition();
        double currentYPosition = core_.getYPosition();

        double correctedXPosition = currentXPosition;
        double correctedYPosition = currentYPosition;

        double xCorrection = 0;
        double yCorrection = 0;

        if (xy_correction.contentEquals("Yes")){
            int[] dxdy;
            //Define current image as reference for the position if it does not exist
            if (!refImageDict.containsKey(label)) {
                refImageDict.put(label, currentImg);
            } else {
                //Or calculate XY drift
                TaggedImage imgRef = refImageDict.get(label);

                /*
                    CV_TM_SQDIFF        = 0,
                    CV_TM_SQDIFF_NORMED = 1,
                    CV_TM_CCORR         = 2,
                    CV_TM_CCORR_NORMED  = 3,
                    CV_TM_CCOEFF        = 4,
                    CV_TM_CCOEFF_NORMED = 5;
                 */
                FloatProcessor rFp = doMatch(taggedImgToImgProcessor(imgRef), taggedImgToImgProcessor(currentImg),
                        3);
                dxdy = findMax(rFp, 0);

                xCorrection = dxdy[0];
                yCorrection = dxdy[1];
                if (Double.isNaN(xCorrection) || Double.isNaN(yCorrection)){
                    xCorrection = 0;
                    yCorrection = 0;
                }
                correctedXPosition = currentXPosition + xCorrection;
                correctedYPosition = currentYPosition + yCorrection;

            }
            long endTime = new Date().getTime();
            long acquisitionTimeElapsed = endTime - startTime;
            ReportingUtils.logMessage("Acquisition duration in ms : " + acquisitionTimeElapsed);

            setXYPosition(correctedXPosition, correctedYPosition);

            if (xCorrection != 0 && yCorrection != 0) {
                //Reference image incremental
                core_.waitForDevice(core_.getCameraDevice());
                core_.snapImage();
                TaggedImage newRefTaggedImage = core_.getTaggedImage();
                refImageDict.replace(label, newRefTaggedImage);
            }
        }

        //Reset conditions
        resetInitialMicroscopeCondition(oldROI, oldState, oldExposure, oldAutoShutterState);

        //Set to the focus
        setZPosition(correctedZPosition);

        //Refresh positions in position dictionary
        refreshOldXYZposition(correctedXPosition, correctedYPosition, correctedZPosition, label);
        if (positionList.getNumberOfPositions() == 0) {
            timepoint++;
        }
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
        positionIndex = 0;
        imageCount = 0;
        timepoint = 0;
        IJ.log("BF AutoFocus internal parameters have been reset");
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

    private void setXYPosition(double x, double y) throws Exception {
        assert x != 0;
        assert y != 0;
        String xyDevice = core_.getXYStageDevice();
        core_.setXYPosition(x,y);
        core_.waitForDevice(xyDevice);
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

    public static FloatProcessor doMatch(ImagePlus src, ImagePlus tpl, int method) {

        return doMatch(src.getProcessor(), tpl.getProcessor(), method);

    }

    private static FloatProcessor doMatch(ImageProcessor src, ImageProcessor tpl, int method) {

        BufferedImage bi, bi2;
        FloatProcessor resultFp;
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        int tplW = tpl.getWidth();
        int tplH = tpl.getHeight();
        IplImage temp, temp2,res;
        IplImage iplSrc = null;
        IplImage iplTpl = null;

        switch (src.getBitDepth()) {

            case 32:
                //convert source imageProcessor into iplImage
                CvMat srcMat = CvMat.create(srcH, srcW, opencv_core.CV_32FC1);
                double[] dArr1 = float2DtoDouble1DArray(src.getFloatArray(), srcW, srcH);
                srcMat.put(0, dArr1, 0, dArr1.length);
                iplSrc = srcMat.asIplImage();
                //iplSrc = temp.clone();

                //convert template imageProcessor into iplImage
                CvMat tplMat = CvMat.create(tplH, tplW, opencv_core.CV_32FC1);
                double[] dArr2 = float2DtoDouble1DArray(tpl.getFloatArray(), tplW, tplH);
                tplMat.put(0, dArr2, 0, dArr2.length);
                iplTpl = tplMat.asIplImage();
                //iplTpl = temp2.clone();

                break;
            case 16:
                //since cvMatchTemplate don't accept 16bit image, we have to convert it to 32bit
                iplSrc = opencv_core.cvCreateImage(opencv_core.cvSize(srcW, srcH), opencv_core.IPL_DEPTH_32F, 1);
                bi = ((ShortProcessor) src).get16BitBufferedImage();
                temp = toIplImage(bi);
//                temp = IplImage.createFrom(bi);
                opencv_core.cvConvertScale(temp, iplSrc, 1 / 65535.0, 0);

                iplTpl = opencv_core.cvCreateImage(opencv_core.cvSize(tplW, tplH), opencv_core.IPL_DEPTH_32F, 1);
                bi2 = ((ShortProcessor) tpl).get16BitBufferedImage();
                temp2 = toIplImage(bi2);
                opencv_core.cvConvertScale(temp2, iplTpl, 1 / 65535.0, 0);

                temp.release();
                temp2.release();

                break;
            case 8:

                bi = src.getBufferedImage();
                iplSrc = toIplImage(bi);

                bi2 = tpl.getBufferedImage();
                iplTpl = toIplImage(bi2);

                break;
            default:
                IJ.error("Unsupported image type");
                break;
        }

        res =  opencv_core.cvCreateImage(opencv_core.cvSize(srcW - tplW + 1, srcH - tplH + 1),
                opencv_core.IPL_DEPTH_32F, 1);

        /*
        CV_TM_SQDIFF        = 0,
        CV_TM_SQDIFF_NORMED = 1,
        CV_TM_CCORR         = 2,
        CV_TM_CCORR_NORMED  = 3,
        CV_TM_CCOEFF        = 4,
        CV_TM_CCOEFF_NORMED = 5;
         */

        opencv_imgproc.cvMatchTemplate(iplSrc, iplTpl, res, method);
        FloatBuffer fb = res.getFloatBuffer();
        float[] f = new float[res.width() * res.height()];
        fb.get(f, 0, f.length);
        resultFp = new FloatProcessor(res.width(), res.height(), f, null);
        opencv_core.cvReleaseImage(res);

        switch (src.getBitDepth()) {
            case 32:
                iplSrc.release();
                iplTpl.release();
                break;
            case 16:
                opencv_core.cvReleaseImage(iplSrc);
                opencv_core.cvReleaseImage(iplTpl);
                break;
            case 8:
                iplSrc.release();
                iplTpl.release();
                break;
            default:
                break;
        }

        return resultFp;
    }

    private ImageProcessor taggedImgToImgProcessor(TaggedImage taggedImage) throws JSONException {
        return studio_.data().getImageJConverter().createProcessor(studio_.data().convertTaggedImage(taggedImage));
    }

    public static int[] findMax(ImageProcessor ip, int sW) {
        int[] coord = new int[2];
        float max = ip.getPixel(0, 0);
        int sWh, sWw;

        if (sW == 0) {
            sWh = ip.getHeight();
            sWw = ip.getWidth();
        } else {
            sWh = sW;
            sWw = sW;
        }


        for (int j = (ip.getHeight() - sWh) / 2; j < (ip.getHeight() + sWh) / 2; j++) {
            for (int i = (ip.getWidth() - sWw) / 2; i < (ip.getWidth() + sWw) / 2; i++) {
                if (ip.getPixel(i, j) > max) {
                    max = ip.getPixel(i, j);
                    coord[0] = i;
                    coord[1] = j;
                }
            }
        }
        return (coord);
    }

    /*
     *  The 2D array from ImageJ is [x][y], while the cvMat is in [y][x]
     */
    private static double[] float2DtoDouble1DArray(float[][] arr2d, int column, int row) {



//        IJ.log("src.col: " + column);
//        IJ.log("src.row: "+ row);
//        IJ.log("arr2d[]: "+ arr2d.length);
//        IJ.log("arr2d[][]: "+ arr2d[0].length);


        double[] arr1d = new double[column * row];
        for (int y = 0; y < row; y++) {
            for (int x = 0; x < column; x++) {
//                IJ.log("convert x:"+x+" y: "+y );
//                IJ.log("arr1d x*row+y:"+(y*column+x));
//                IJ.log("arr1d value:"+ arr2d[x][y]);
                arr1d[y * column + x] = (double) arr2d[x][y];
            }
        }

        return arr1d;
    }

    private static IplImage toIplImage(BufferedImage bufImage) {

        OpenCVFrameConverter.ToIplImage iplConverter = new OpenCVFrameConverter.ToIplImage();
        Java2DFrameConverter java2dConverter = new Java2DFrameConverter();
        return iplConverter.convert(java2dConverter.convert(bufImage));
    }
}

