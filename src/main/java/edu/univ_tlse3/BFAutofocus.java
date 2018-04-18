package edu.univ_tlse3;

import ij.process.ImageProcessor;
import mmcorej.CMMCore;
import mmcorej.Configuration;
import mmcorej.StrVector;
import mmcorej.TaggedImage;
import org.micromanager.AutofocusPlugin;
import org.micromanager.Studio;
import org.micromanager.data.Image;
import org.micromanager.internal.utils.*;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.SciJavaPlugin;

import javax.swing.*;
import java.awt.*;
import java.text.ParseException;

@Plugin(type = AutofocusPlugin.class)
public class BFAutofocus extends AutofocusBase implements AutofocusPlugin, SciJavaPlugin{
   private static final String VERSION_INFO = "1.0.0";
   private static final String NAME = "Bright-field autofocus";
   private static final String HELPTEXT = "This simple autofocus is only designed to process transmitted-light (or DIC) images, Z-stack is required.";
   private static final String COPYRIGHT_NOTICE = "CeCILL-B-BSD compatible";
   private Studio studio_;

   private static final String SEARCH_RANGE = "SearchRange_um";
   private static final String CROP_FACTOR = "CropFactor";
   private static final String CHANNEL = "Channel";
   private static final String EXPOSURE = "Exposure";
   private static final String SHOW_IMAGES = "ShowImages";
   private static final String[] SHOWVALUES = {"Yes", "No"};
   private static final String STEP_SIZE = "Step_size";
   private static final String Z_OFFSET = "Z offset";

   private double searchRange = 6;
   private double cropFactor = 1;
   private String channel = "BF";
   private double exposure = 60;
   private String show = "Yes";
   private int imageCount_;
   private double step = 0.3;
   private double zOffset = 0;

   public BFAutofocus() {
      super.createProperty(SEARCH_RANGE, NumberUtils.doubleToDisplayString(searchRange));
      super.createProperty(CROP_FACTOR, NumberUtils.doubleToDisplayString(cropFactor));
      super.createProperty(EXPOSURE, NumberUtils.doubleToDisplayString(exposure));
      super.createProperty(Z_OFFSET, NumberUtils.doubleToDisplayString(zOffset));
      super.createProperty(SHOW_IMAGES, show, SHOWVALUES);
      super.createProperty(STEP_SIZE, NumberUtils.doubleToDisplayString(step));
      super.createProperty(CHANNEL, channel);
   }

   @Override
   public void applySettings() {
      try {
         searchRange = NumberUtils.displayStringToDouble(getPropertyValue(SEARCH_RANGE));
         cropFactor = NumberUtils.displayStringToDouble(getPropertyValue(CROP_FACTOR));
         cropFactor = MathFunctions.clip(0.01, cropFactor, 1.0);
         channel = getPropertyValue(CHANNEL);
         exposure = NumberUtils.displayStringToDouble(getPropertyValue(EXPOSURE));
         zOffset = NumberUtils.displayStringToDouble(getPropertyValue(Z_OFFSET));
         show = getPropertyValue(SHOW_IMAGES);

      } catch (MMException ex) {
         studio_.logs().logError(ex);
      } catch (ParseException ex) {
         studio_.logs().logError(ex);
      }
   }

   @Override
   public double fullFocus() throws Exception {
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
      setZPosition(z + zOffset);
      return z;
   }

   @Override
   public double incrementalFocus() throws Exception {
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

   private double runAutofocusAlgorithm() throws Exception {
      CMMCore core = studio_.getCMMCore();
      double oldZ = core.getPosition(core.getFocusDevice());
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
            SwingUtilities.invokeLater(() -> {
               try {
                  studio_.live().displayImage(img);
               }
               catch (IllegalArgumentException e) {
                  studio_.logs().showError(e);
               }
            });
         }
      }

      core.setAutoShutter(oldAutoShutterState);
      int rawIndex = getZfocus(stdAtZPositions);
      return optimizeZFocus(rawIndex, stdAtZPositions, zpositions);
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
}

