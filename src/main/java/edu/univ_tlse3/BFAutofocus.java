package edu.univ_tlse3;

import ij.IJ;
import ij.ImagePlus;
import ij.measure.ResultsTable;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import mmcorej.CMMCore;
import mmcorej.Configuration;
import mmcorej.StrVector;
import mmcorej.TaggedImage;
import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacpp.opencv_core.CvMat;
import org.bytedeco.javacpp.opencv_core.IplImage;
import org.bytedeco.javacpp.opencv_imgproc;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.json.JSONException;
import org.micromanager.AutofocusPlugin;
import org.micromanager.PositionList;
import org.micromanager.Studio;
import org.micromanager.data.*;
import org.micromanager.data.Image;
import org.micromanager.data.internal.DefaultCoords;
import org.micromanager.data.internal.DefaultMetadata;
import org.micromanager.data.internal.DefaultSummaryMetadata;
import org.micromanager.internal.utils.*;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.SciJavaPlugin;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

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
	private static final String XY_CORRECTION_TEXT = "XY Correction";
    private static final String D0_SUBPIXEL = "Sub pixel ?";
    private static final String[] D0_SUBPIXEL_VALUES = {"Yes", "No"};
    private static final String[] SHOWIMAGES_VALUES = {"Yes", "No"};
	private static final String[] SAVEIMAGES_VALUES = {"Yes", "No"};
	private static final String STEP_SIZE = "Step_size";
	private static final String[] XY_CORRECTION_VALUES = {"Yes", "No"};
	private static final String Z_OFFSET = "Z offset";

	//Set default parameters
	private double searchRange = 3;
	private double cropFactor = 1;
	private String channel = "BF";
	private double exposure = 50;
	private String show = "Yes";
	private String save = "No";
    private String subPixel = "Yes";
	private int imageCount = 0;
	private int timepoint = 0;
	private double step = 0.5;
	private String xy_correction = "Yes";
	private Map<String, TaggedImage> refImageDict = new HashMap<>();
	private Map<String, double[]> oldPositionsDict = new HashMap<>();
	private double zOffset = -1;

	//Global variables
	private Studio studio_;
	private CMMCore core_;
	private int positionIndex = 0;
	private Datastore store;
	private ResultsTable rt;


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
		super.createProperty(SAVEIMGS_TEXT, save, SAVEIMAGES_VALUES);
		super.createProperty(D0_SUBPIXEL, subPixel, D0_SUBPIXEL_VALUES);
		rt = ResultsTable.getResultsTable();
	}

	private static Integer[] getZfocus(double[] stdArray) {
		double min = Double.MAX_VALUE;
		for (double aStd : stdArray) {
			if (aStd < min) {
				min = aStd;
			}
		}
		ArrayList minIdxs = new ArrayList<Integer>();
		for (int i = 0; i < stdArray.length; i++) {
			if (stdArray[i] == min) {
				minIdxs.add(i);
			}
		}
		return (Integer[]) minIdxs.toArray(new Integer[]{});
	}

	public static double[] calculateZPositions(double searchRange, double step, double startZUm) {
		double lower = startZUm - searchRange / 2;
		int nstep = new Double(searchRange / step).intValue() + 1;
		double[] zpos = new double[nstep];
		for (int p = 0; p < nstep; p++) {
			zpos[p] = lower + p * step;
		}
		return zpos;
	}

	//*******//
	//Methods//
	//*******//

	private static double optimizeZFocus(int rawZidx, double[] stdArray, double[] zpositionArray) {
		if (rawZidx == zpositionArray.length - 1 || rawZidx == 0) {
			return zpositionArray[rawZidx];
		}
		int oneLower = rawZidx - 1;
		int oneHigher = rawZidx + 1;
		double lowerVarDiff = stdArray[oneLower] - stdArray[rawZidx];
		double upperVarDiff = stdArray[rawZidx] - stdArray[oneHigher];
		if (lowerVarDiff * lowerVarDiff < upperVarDiff * upperVarDiff) {
			return (zpositionArray[oneLower] + zpositionArray[rawZidx]) / 2;
		} else if (lowerVarDiff * lowerVarDiff > upperVarDiff * upperVarDiff) {
			return (zpositionArray[rawZidx] + zpositionArray[oneHigher]) / 2;
		} else {
			return zpositionArray[rawZidx];
		}
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
		IplImage temp, temp2, res;
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

		res = opencv_core.cvCreateImage(opencv_core.cvSize(srcW - tplW + 1, srcH - tplH + 1),
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

	//XYZ-Methods

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

	//Z-Methods

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
			save = getPropertyValue(SAVEIMGS_TEXT);
			subPixel = getPropertyValue(D0_SUBPIXEL);
		} catch (MMException | ParseException ex) {
			studio_.logs().logError(ex);
		}
	}

	@Override
	public double fullFocus() throws Exception {
		long startTime = new Date().getTime();
		double calib = studio_.core().getPixelSizeUm();
		applySettings();
		Rectangle oldROI = studio_.core().getROI();
		core_ = studio_.getCMMCore();
		String savingPath = studio_.acquisitions().getAcquisitionSettings().root + File.separator;

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
		core_.setAutoShutter(true);

		//Get label of position
		PositionList positionList = studio_.positions().getPositionList();
		String label;
		if (positionList.getNumberOfPositions() == 0) {
			if (positionIndex > 0) {
				positionIndex = 0;
			}
			label = positionList.generateLabel();
		} else {
			label = positionList.getPosition(positionIndex).getLabel();
		}

		//Creation of BF saving directory
		String bfPath = savingPath + "BFs";
		if (save.contentEquals("Yes")) {
			try {
				store = studio_.data().createMultipageTIFFDatastore(
						bfPath, false, true);
				if (show.contentEquals("Yes")) {
					studio_.displays().createDisplay(store);
				}
			} catch (Exception e) {
//				throw new FileAlreadyExistsException("Data store already exist");
			}
		}

		double currentZ = getZPosition();
		double lastZ = Double.MAX_VALUE;

		//Define positions if it does not exist
		if (!oldPositionsDict.containsKey(label)) {
			double[] currentPositions = new double[3];
			currentPositions[0] = core_.getXPosition();
			currentPositions[1] = core_.getYPosition();
			currentPositions[2] = currentZ;
			oldPositionsDict.put(label, currentPositions);
		} else {
			//Set to the last good position calculated
			double[] oldXYZ = oldPositionsDict.get(label);
//			setXYPosition(oldXYZ[0], oldXYZ[1]);
//			setZPosition(oldXYZ[2]);
			lastZ = oldXYZ[2];
		}

		//Calculate Focus
		double correctedZPosition = calculateZFocus(currentZ);
		double delta = -1;

		if (lastZ != Double.MAX_VALUE) {
			double k = 0.4;
			delta = lastZ - correctedZPosition;
			correctedZPosition = lastZ - delta * k;
		}
		ReportingUtils.logMessage("Corrected Z Position : " + correctedZPosition);

		double currentXPosition = core_.getXPosition();
		double currentYPosition = core_.getYPosition();

		double correctedXPosition = currentXPosition;
		double correctedYPosition = currentYPosition;

		//Set to the focus  + offset in order to enhance the signal
		setZPosition(correctedZPosition + zOffset);

		if (xy_correction.contentEquals("Yes")) {
			//Get an image to define reference image, for each position
			core_.waitForDevice(core_.getCameraDevice());
			core_.snapImage();

			TaggedImage currentImg = core_.getTaggedImage();

			double xCorrection = 0;
			double yCorrection = 0;

			//Define current image as reference for the position if it does not exist
			if (!refImageDict.containsKey(label)) {
				refImageDict.put(label, currentImg);
			} else {
				//Or calculate XY drift
				TaggedImage imgRef = refImageDict.get(label);

				ImageProcessor proc = taggedImgToImgProcessor(imgRef);

				int fieldWidth = (int) core_.getImageWidth();
				int fieldHeight = (int) core_.getImageHeight();

				int cropMagnitude = 4;
				int newW = Math.floorDiv(fieldWidth, cropMagnitude);
				int newH = Math.floorDiv(fieldHeight, cropMagnitude);
				int selectionOriginX = Math.floorDiv(fieldWidth, 2) - newW / 2;
				int selectionOriginY = Math.floorDiv(fieldHeight, 2) - newH / 2;

				proc.setRoi(selectionOriginX, selectionOriginY, newW, newH);

                /*
                    CV_TM_SQDIFF        = 0,
                    CV_TM_SQDIFF_NORMED = 1,
                    CV_TM_CCORR         = 2,
                    CV_TM_CCORR_NORMED  = 3,
                    CV_TM_CCOEFF        = 4,
                    CV_TM_CCOEFF_NORMED = 5;
                 */
				FloatProcessor rFp = doMatch(taggedImgToImgProcessor(currentImg), proc.crop(), 3);
				int[] dxdy = findMax(rFp, 0);

                if (subPixel.contentEquals("Yes")) {
                    double[] dxdyG = gaussianPeakFit(rFp, dxdy[0], dxdy[1]);
                    xCorrection = (selectionOriginX - dxdyG[0]) * calib;
                    yCorrection = (selectionOriginY - dxdyG[1]) * calib;
                } else {
                    xCorrection = (selectionOriginX - dxdy[0]) * calib;
                    yCorrection = (selectionOriginY - dxdy[1]) * calib;
                }

				if (Double.isNaN(xCorrection) || Double.isNaN(yCorrection)) {
					ReportingUtils.logMessage("Drift correction failed at position " + label + " timepoint " + timepoint);
					xCorrection = 0;
					yCorrection = 0;
				}

				ReportingUtils.logMessage("X Correction : " + xCorrection);
				ReportingUtils.logMessage("Y Correction : " + yCorrection);

                rt.incrementCounter();
				rt.addValue("Position", label);
				rt.addValue("Slice", timepoint);
                rt.addValue("dX", xCorrection);
                rt.addValue("dY", yCorrection);
                rt.addValue("dZ", delta);
                rt.updateResults();
                rt.show("Results");

				correctedXPosition = currentXPosition - xCorrection;
				correctedYPosition = currentYPosition - yCorrection;

				long endTime = new Date().getTime();
				long acquisitionTimeElapsed = endTime - startTime;
				ReportingUtils.logMessage("Acquisition duration in ms : " + acquisitionTimeElapsed);
			}

			//If XY Correction, new coordinates; else, corrected = current coordinates;
			if (xCorrection != 0 || yCorrection != 0) {
				setXYPosition(correctedXPosition, correctedYPosition);
			}
		}
		core_.waitForDevice(core_.getCameraDevice());
		core_.snapImage();
		TaggedImage newRefImg = core_.getTaggedImage();

		if (xy_correction.contentEquals("Yes")) {
			refImageDict.replace(label, newRefImg);
		}

		Image img = getImageFromTaggedImg(newRefImg, 0);

		if (show.contentEquals("Yes")) {
			SwingUtilities.invokeLater(() -> {
				try {
					studio_.live().displayImage(img);
				} catch (IllegalArgumentException e) {
					studio_.logs().showError(e);
				}
			});
		}

		if (save.contentEquals("Yes")) {
			assert store != null;
			store.putImage(img);
		}

		finalizeAcquisition(oldROI, oldState, oldExposure, oldAutoShutterState, positionList, label,
				savingPath, correctedZPosition, correctedXPosition, correctedYPosition);
		return correctedZPosition;
	}

	//Reinitialize counters and dictionaries
	private void resetParameters() {
		refImageDict = new HashMap<>();
		oldPositionsDict = new HashMap<>();
		positionIndex = 0;
		store = null;
		imageCount = 0;
		timepoint = 0;
		rt.reset();
		IJ.run("Clear Results", "");
		IJ.log("BF AutoFocus internal parameters have been reset");
	}

	//Reinitialize origin ROI and all other parameters
	private void resetInitialMicroscopeCondition(Rectangle oldROI, Configuration oldState, double oldExposure,
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

	private void finalizeAcquisition(Rectangle oldROI, Configuration oldState, double oldExposure, boolean oldAutoShutterState,
	                                 PositionList positionList, String label, String savingPath, double correctedZPosition,
	                                 double correctedXPosition, double correctedYPosition) {
		//Reset conditions
		resetInitialMicroscopeCondition(oldROI, oldState, oldExposure, oldAutoShutterState);

		//Set to the focus
		setZPosition(correctedZPosition);

		//Refresh positions in position dictionary
		refreshOldXYZposition(correctedXPosition, correctedYPosition, correctedZPosition, label);

		//Save datastore if acquisition stopped/ all timepoints for all positions have been acquired
		if (!studio_.acquisitions().isAcquisitionRunning() ||
				(timepoint >= studio_.acquisitions().getAcquisitionSettings().numFrames - 1
						&& (positionList.getNumberOfPositions() - 1 == positionIndex ||
						positionList.getNumberOfPositions() < 1))) {
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
				store.save(Datastore.SaveMode.MULTIPAGE_TIFF, savingPath + "ordered_focus_imgs");
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
			String currentTime = LocalDateTime.now()
					.format(DateTimeFormatter.ofPattern("MM-dd-HH-mm"));
			if (savingPath == null){
				savingPath = IJ.getDirectory("macros");
			}
			IJ.saveAs("Results", savingPath + "Drift_corrections_" + currentTime + ".csv");
			resetParameters();

			//Increment timepoint and positionIndex if acquisition still running
		} else {
			if (positionList.getNumberOfPositions() == 0) {
				timepoint++;
			} else if (positionList.getNumberOfPositions() == 1) {
				timepoint++;
			} else {
				positionIndex++;
				if (positionIndex == positionList.getNumberOfPositions()) {
					positionIndex = 0;
					timepoint++;
				}
			}
		}
	}

	private void refreshOldXYZposition(double correctedXPosition, double correctedYPosition, double correctedZPosition, String label) {
		oldPositionsDict.replace(label, new double[]{correctedXPosition, correctedYPosition, correctedZPosition});
	}

	private double getZPosition() {
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

	private void setZPosition(double z) {
		String focusDevice = core_.getFocusDevice();
		try {
			core_.setPosition(focusDevice, z);
			core_.waitForDevice(focusDevice);
		} catch (Exception e) {
			e.printStackTrace();
			ReportingUtils.showError("  Unable to set Z Position");
		}
	}

    private double[] gaussianPeakFit(ImageProcessor ip, int x, int y) {
        double[] coord = new double[2];
        // border values
        if (x == 0
                || x == ip.getWidth() - 1
                || y == 0
                || y == ip.getHeight() - 1) {
            coord[0] = x;
            coord[1] = y;
        } else {
            coord[0] = x
                    + (Math.log(ip.getPixel(x - 1, y))
                    - Math.log(ip.getPixel(x + 1, y)))
                    / (2 * Math.log(ip.getPixel(x - 1, y))
                    - 4 * Math.log(ip.getPixel(x, y))
                    + 2 * Math.log(ip.getPixel(x + 1, y)));
            coord[1] = y
                    + (Math.log(ip.getPixel(x, y - 1))
                    - Math.log(ip.getPixel(x, y + 1)))
                    / (2 * Math.log(ip.getPixel(x, y - 1))
                    - 4 * Math.log(ip.getPixel(x, y))
                    + 2 * Math.log(ip.getPixel(x, y + 1)));
        }
        return (coord);
    }

	private double calculateZFocus(double oldZ) throws Exception {
		double[] zPositions = calculateZPositions(searchRange, step, oldZ);
		double[] stdAtZPositions = new double[zPositions.length];
		TaggedImage currentImg = null;
		for (int i = 0; i < zPositions.length; i++) {
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
			Image img = getImageFromTaggedImg(currentImg, i);
			stdAtZPositions[i] = studio_.data().ij().createProcessor(img).getStatistics().stdDev;
		}
		Integer[] rawIndexs = getZfocus(stdAtZPositions);
		int minIndexLength = rawIndexs.length;
		int minIndex = rawIndexs[(int) Math.floor(minIndexLength / 2.0)];
		return optimizeZFocus(minIndex, stdAtZPositions, zPositions);
	}

	private Image getImageFromTaggedImg(TaggedImage currentImg, int i) {
		Image img = null;
		Metadata metadata = DefaultMetadata.legacyFromJSON(currentImg.tags);
		PositionList posList = studio_.positions().getPositionList();
		if (posList.getNumberOfPositions() > 0) {
			metadata = metadata.copy().positionName(posList.getPosition(positionIndex).getLabel()).build();
		}
		try {
			img = studio_.data().convertTaggedImage(currentImg,
					studio_.data().getCoordsBuilder().z(i).channel(0).stagePosition(positionIndex).time(timepoint).build(),
					metadata);
		} catch (JSONException e) {
			e.printStackTrace();
			ReportingUtils.showError("Unable to process z image at " + i);
		}
		return img;
	}

	private void setXYPosition(double x, double y) {
		assert x != 0;
		assert y != 0;
		String xyDevice = core_.getXYStageDevice();
		try {
			core_.setXYPosition(x, y);
			core_.waitForDevice(xyDevice);
		} catch (Exception e) {
			e.printStackTrace();
			ReportingUtils.showError("Unable to set XY position");
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
		String allowedChannels[] = new String[(int) channels.size() + 1];
		allowedChannels[0] = "";

		try {
			PropertyItem p = getProperty(CHANNEL);
			boolean found = false;
			for (int i = 0; i < channels.size(); i++) {
				allowedChannels[i + 1] = channels.get(i);
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

	private ImageProcessor taggedImgToImgProcessor(TaggedImage taggedImage) throws JSONException {
		return studio_.data().getImageJConverter().createProcessor(studio_.data().convertTaggedImage(taggedImage));
	}
}

