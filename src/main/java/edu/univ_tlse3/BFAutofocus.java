package edu.univ_tlse3;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import mmcorej.CMMCore;
import mmcorej.Configuration;
import mmcorej.StrVector;
import mmcorej.TaggedImage;
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

import java.awt.*;
import java.io.File;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
	public static final String SHOWIMAGES_TEXT = "ShowImages";
	public static final String SAVEIMGS_TEXT = "SaveImages";
	public static final String[] SHOWIMAGES_VALUES = {"Yes", "No"};
	public static final String[] SAVEIMAGES_VALUES = {"Yes", "No"};
	public static final String STEP_SIZE = "Step_size";
	public static final String[] XY_CORRECTION_VALUES = {"Yes", "No"};
	public static final String Z_OFFSET = "Z offset";
	
	//Set default parameters
	public double searchRange = 10;
	public double cropFactor = 1;
	public String channel = "BF";
	public double exposure = 50;
	public String show = "Yes";
	public String save = "Yes";
	public int imageCount = 0;
	public int timepoint = 0;
	public double step = 0.3;
	public String xy_correction = "Yes";
	public Map<String, ImagePlus> refImageDict = new HashMap<>();
	public Map<String, double[]> oldPositionsDict = new HashMap<>();
	public double umPerStep = 10;
	public double zOffset = -1;
	
	//Global variables
	public Studio studio_;
	public CMMCore core_;
	public ImagePlus imgRef_Mat = null;
	public double calibration = 0;
	public double intervalInMin = 0;
	public int positionIndex = 0;
	public String savingPath;
	public Datastore store;
	
	//Begin autofocus
	public BFAutofocus() {
		super.createProperty(SEARCH_RANGE, NumberUtils.doubleToDisplayString(searchRange));
		super.createProperty(CROP_FACTOR, NumberUtils.doubleToDisplayString(cropFactor));
		super.createProperty(EXPOSURE, NumberUtils.doubleToDisplayString(exposure));
		super.createProperty(Z_OFFSET, NumberUtils.doubleToDisplayString(zOffset));
		super.createProperty(SHOWIMAGES_TEXT, show, SHOWIMAGES_VALUES);
		super.createProperty(STEP_SIZE, NumberUtils.doubleToDisplayString(step));
		super.createProperty(CHANNEL, channel);
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
			channel = getPropertyValue(CHANNEL);
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
		intervalInMin = (studio_.acquisitions().getAcquisitionSettings().intervalMs) / 60000;
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
		
		if (positionList.getNumberOfPositions() == 0) {
			if (positionIndex > 0) {
				positionIndex = 0;
			}
			label = positionList.generateLabel();
		} else {
			label = getLabelOfPositions(positionList);
		}
		
		ReportingUtils.logMessage("Position : " + label + " at time point : " + timepoint);
		
		//Creation of BF saving directory
		String bfPath = savingPath + "BFs";
		if (save.contentEquals("Yes") && !new File(bfPath).exists()) {
			store = studio_.data().createMultipageTIFFDatastore(
					bfPath, false, true);
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
		ImagePlus currentImp = taggedImgToImagePlus(core_.getTaggedImage());
		
		//Calculation of XY Drifts only if the parameter "Correct XY at same time" is set to Yes;
		double currentXPosition = core_.getXPosition();
		double currentYPosition = core_.getYPosition();
		
		double correctedXPosition = currentXPosition;
		double correctedYPosition = currentYPosition;
		
		double xCorrection;
		double yCorrection;
		
		if (xy_correction.contentEquals("Yes")) {
			double currentZPosition = oldZ;
			double[] drifts = new double[11];
			//Define current image as reference for the position if it does not exist
			if (!refImageDict.containsKey(label)) {
				refImageDict.put(label, currentImp);
			} else {
				//Or calculate XY drift
				imgRef_Mat = refImageDict.get(label);
				
				drifts = calculateXYDrifts(currentImp, oldROI, oldState, oldExposure, oldAutoShutterState,
						positionList, label, bfPath, correctedZPosition, correctedXPosition, correctedYPosition);
				
				xCorrection = drifts[0] * calibration;
				yCorrection = drifts[1] * calibration;
				
				if (Double.isNaN(xCorrection) || Double.isNaN(yCorrection)) {
					ReportingUtils.logMessage("Drift correction failed at position " + label + " timepoint " + timepoint);
					xCorrection = 0;
					yCorrection = 0;
				}
//				else if (Math.abs(xCorrection) < threshold) {
//					xCorrection = 0;
//				} else if (Math.abs(yCorrection) < threshold) {
//					yCorrection = 0;
//				}
				
				ReportingUtils.logMessage("X Correction : " + xCorrection);
				ReportingUtils.logMessage("Y Correction : " + yCorrection);
				
				correctedXPosition = currentXPosition + xCorrection;
				correctedYPosition = currentYPosition + yCorrection;
				
				long endTime = new Date().getTime();
				long acquisitionTimeElapsed = endTime - startTime;
				ReportingUtils.logMessage("Acquisition duration in ms : " + acquisitionTimeElapsed);
			}
			
			//If XY Correction, new coordinates; else, corrected = current coordinates;
			setXYPosition(correctedXPosition, correctedYPosition);
			
			//Reference image incremental
			core_.waitForDevice(core_.getCameraDevice());
			core_.snapImage();
			ImagePlus newRefMat = taggedImgToImagePlus(core_.getTaggedImage());
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
	
	//Reinitialization methods
	
	//Reinitialize counters and dictionaries
	public void resetParameters() {
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
	
	private void finalizeAcquisition(Rectangle oldROI, Configuration oldState, double oldExposure, boolean oldAutoShutterState,
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
				(timepoint == studio_.acquisitions().getAcquisitionSettings().numFrames - 1
						&& store.getAxisLength("position") - 1 == positionIndex)) {
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
				store.save(Datastore.SaveMode.MULTIPAGE_TIFF, bfPath + "_ordered");
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
	
	public static int getZfocus(double[] stdArray) {
		double min = Double.MAX_VALUE;
		int maxIdx = Integer.MAX_VALUE;
		for (int i = 0; i < stdArray.length; i++) {
			if (stdArray[i] < min) {
				maxIdx = i;
				min = stdArray[i];
			}
		}
		return maxIdx;
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
	
	public static double optimizeZFocus(int rawZidx, double[] stdArray, double[] zpositionArray) {
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
	
	public double calculateZFocus(double oldZ, boolean save) {
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
			Metadata metadata = DefaultMetadata.legacyFromJSON(currentImg.tags);
			PositionList posList = studio_.positions().getPositionList();
			if (posList.getNumberOfPositions() > 0) {
				metadata = metadata.copy().positionName(posList.getPosition(positionIndex).getLabel()).build();
			}
			Image img = null;
			try {
				img = studio_.data().convertTaggedImage(currentImg,
						studio_.data().getCoordsBuilder().z(i).channel(0).stagePosition(positionIndex).time(timepoint).build(),
						metadata);
				if (save) {
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
	
	public double[] calculateXYDrifts(ImagePlus currentImg, Rectangle oldROI, Configuration oldState, double oldExposure, boolean oldAutoShutterState,
												 PositionList positionList, String label, String bfPath, double correctedZPosition,
												 double correctedXPosition, double correctedYPosition) {
		
		ExecutorService es = Executors.newSingleThreadExecutor();
		Future job = es.submit(new ThreadAttribution(imgRef_Mat, currentImg));
		double[] xyDrifts = new double[2];
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
	
	public void setXYPosition(double x, double y) {
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
	
	private ImagePlus taggedImgToImagePlus(TaggedImage taggedImage) throws Exception {
		return new ImagePlus(timepoint + "", studio_.data().getImageJConverter().createProcessor(studio_.data().convertTaggedImage(taggedImage)));
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
	
	//********************************************************************************//
	//*************************** Class for multithreading ***************************//
	//********************************************************************************//
	public static class ThreadAttribution implements Callable<double[]> {
		
		private ImagePlus img1_;
		private ImagePlus img2_;
		
		public ThreadAttribution(ImagePlus img1, ImagePlus img2) {
			img1_ = img1;
			img2_ = img2;
		}
		
		@Override
		public double[] call() {
			final int width = img1_.getWidth();
			final int height = img2_.getHeight();
			final String sourcePathAndFileName = IJ.getDirectory("temp") + UUID.randomUUID().toString() + img1_.getTitle();
			IJ.saveAsTiff(img1_, sourcePathAndFileName);
			final String targetPathAndFileName = IJ.getDirectory("temp") + UUID.randomUUID().toString() + img2_.getTitle();
			IJ.saveAsTiff(img2_, targetPathAndFileName);
			TurboReg_ reg = new TurboReg_();
			reg.run("-align"
					+ " -file " + sourcePathAndFileName
					+ " 0 0 " + (width - 1) + " " + (height - 1)
					+ " -file " + targetPathAndFileName
					+ " 0 0 " + (width - 1) + " " + (height - 1)
					+ " -translation"
					+ " " + (width / 2) + " " + (height / 2)
					+ " " + (width / 2) + " " + (height / 2)
					+ " -hideOutput");
			
			double[][] sourcePoints = reg.getSourcePoints();
			double[][] targetPoints = reg.getTargetPoints();
			double[] xyDrifts = new double[2];
			xyDrifts[0] = targetPoints[0][0] - sourcePoints[0][0];
			xyDrifts[1] = targetPoints[0][1] -  sourcePoints[0][1];
			return xyDrifts;
		}
	}
}

