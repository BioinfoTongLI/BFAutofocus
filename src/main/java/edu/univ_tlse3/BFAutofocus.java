package edu.univ_tlse3;

import ij.process.ImageProcessor;
import mmcorej.CMMCore;
import mmcorej.Configuration;
import mmcorej.TaggedImage;
import org.json.JSONException;
import org.micromanager.AutofocusPlugin;
import org.micromanager.Studio;
import org.micromanager.data.Image;
import org.micromanager.internal.utils.*;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.SciJavaPlugin;

import javax.swing.*;
import java.awt.*;
import java.text.ParseException;

@Plugin(type = org.micromanager.AutofocusPlugin.class)
public class BFAutofocus extends AutofocusBase implements AutofocusPlugin, SciJavaPlugin{
   private static final String VERSION_INFO = "1.0.0";
   private static final String NAME = "Bright-field autofocus";
   private static final String DESCRIPTION = "Micro-Manager plugin for z dimension autofocus";
   private static final String COPYRIGHT_NOTICE = "CeCILL-B-BSD compatible";
   private Studio studio_;

   private static final String AF_DEVICE_NAME = "BFFocus";
   private static final String SEARCH_RANGE = "SearchRange_um";
   private static final String TOLERANCE = "Tolerance_um";
   private static final String CROP_FACTOR = "CropFactor";
   private static final String CHANNEL = "Channel";
   private static final String EXPOSURE = "Exposure";
   private static final String SHOW_IMAGES = "ShowImages";
   private static final String SCORING_METHOD = "Maximize";
   private static final String[] SHOWVALUES = {"Yes", "No"};
   private static final String STEP_SIZE = "Step_size";
   private final static String[] SCORINGMETHODS = {"Var"};

   private double searchRange = 10;
//   private double absTolerance = 1.0;
   private double cropFactor = 1;
   private String channel = "";
   private double exposure = 100;
   private String show = "No";
   private String scoringMethod = "Edges";
   private int imageCount_;
   private long startTimeMs_;
   private double startZUm_;
   private boolean liveModeOn_;
   private double step = 0.3;

   public BFAutofocus() {
      super.createProperty(SEARCH_RANGE, NumberUtils.doubleToDisplayString(searchRange));
      super.createProperty(CROP_FACTOR, NumberUtils.doubleToDisplayString(cropFactor));
      super.createProperty(EXPOSURE, NumberUtils.doubleToDisplayString(exposure));
      super.createProperty(SHOW_IMAGES, show, SHOWVALUES);
      super.createProperty(STEP_SIZE, NumberUtils.doubleToDisplayString(step));
      super.createProperty(SCORING_METHOD, scoringMethod, SCORINGMETHODS);
      super.createProperty(CHANNEL, "");
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
         scoringMethod = getPropertyValue(SCORING_METHOD);

      } catch (MMException ex) {
         studio_.logs().logError(ex);
      } catch (ParseException ex) {
         studio_.logs().logError(ex);
      }
   }

   @Override
   public double fullFocus() throws Exception {
      startTimeMs_ = System.currentTimeMillis();
      applySettings();
      Rectangle oldROI = studio_.core().getROI();
      CMMCore core = studio_.getCMMCore();
      liveModeOn_ = studio_.live().getIsLiveModeOn();

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

      double z = runAutofocusAlgorithm();

      if (cropFactor < 1.0) {
         studio_.app().setROI(oldROI);
         core.waitForDevice(core.getCameraDevice());
      }
      if (oldState != null) {
         core.setSystemState(oldState);
      }
      core.setExposure(oldExposure);
      setZPosition(z);
      return z;
   }

   @Override
   public double incrementalFocus() throws Exception {
      return 0;
   }

   @Override
   public int getNumberOfImages() {
      return 0;
   }

   @Override
   public String getVerboseStatus() {
      return null;
   }

   @Override
   public double getCurrentFocusScore() {
      return 0;
   }

   @Override
   public double computeScore(ImageProcessor imageProcessor) {
      return 0;
   }

   @Override
   public void setContext(Studio studio) {
      studio_ = studio;
      studio_.events().registerForEvents(this);
   }

   @Override
   public String getName() {
      return null;
   }

   @Override
   public String getHelpText() {
      return null;
   }

   @Override
   public String getVersion() {
      return null;
   }

   @Override
   public String getCopyright() {
      return null;
   }

   private double runAutofocusAlgorithm() throws Exception {
      CMMCore core = studio_.getCMMCore();
      double z = core.getPosition(core.getFocusDevice());
      startZUm_ = z;
      double[] zpositions = calculateZPositions(searchRange, step, z);
      double[] stdAtZPositions = new double[zpositions.length];
      TaggedImage currentImg;
      for (int i =0; i< zpositions.length ;i++){
         setZPosition(zpositions[i]);
         core.waitForDevice(core.getCameraDevice());
         core.snapImage();
         currentImg = core.getTaggedImage();
         Image img = studio_.data().convertTaggedImage(currentImg);
         stdAtZPositions[i] = studio_.data().ij().createProcessor(img).getStatistics().stdDev;
         if (show.contentEquals("Yes")) {
            SwingUtilities.invokeLater(new Runnable() {
               @Override
               public void run() {
                  try {
                     studio_.live().displayImage(img);
                  }
                  catch (IllegalArgumentException e) {
                     studio_.logs().showError(e);
                  }
               }
            });
         }
      }
      int index = getZfocus(stdAtZPositions);
      return zpositions[index];
   }

   private void setZPosition(double z) throws Exception {
      CMMCore core = studio_.getCMMCore();
      String focusDevice = core.getFocusDevice();
      core.setPosition(focusDevice, z);
      core.waitForDevice(focusDevice);
   }

   public static double[] calculateZPositions(double searchRange, double step, double startZUm_){
      double lower = startZUm_ - searchRange/2;
      int nstep  = new Double(searchRange/step).intValue() + 1;
      double[] zpos = new double[nstep];
      for (int p = 0; p < nstep; p++){
         zpos[p] = lower + p * step;
      }
      return zpos;
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
}

