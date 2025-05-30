/*
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.gui.components;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import org.openpnp.CameraListener;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.components.reticle.Reticle;
import org.openpnp.gui.support.LengthConverter;
import org.openpnp.machine.reference.camera.AbstractBroadcastingCamera;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Camera;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.UiUtils;
import org.openpnp.util.Utils2D;
import org.openpnp.util.VisionUtils;
import org.openpnp.util.XmlSerialize;
import org.pmw.tinylog.Logger;

@SuppressWarnings("serial")
public class CameraView extends JComponent implements CameraListener {
    private static final String PREF_RETICLE = "CamerView.reticle";
    private static final String PREF_ZOOM_INCREMENT = "CamerView.zoomIncrement";
    private static final String PREF_RENDERING_QUALITY = "CamerView.renderingQuality";
    private static final double DEFAULT_ZOOM_INCREMENT = 0.1;

    private static final String DEFAULT_RETICLE_KEY = "DEFAULT_RETICLE_KEY";

    private final static int HANDLE_DIAMETER = 8;

    private enum HandlePosition {
        NW,
        N,
        NE,
        E,
        SE,
        S,
        SW,
        W
    }

    private enum SelectionMode {
        Resizing,
        Moving,
        Creating
    }

    /**
     * The Camera we are viewing.
     */
    private Camera camera;

    /**
     * The last frame received, reported by the Camera.
     */
    private BufferedImage lastFrame;

    private LinkedHashMap<Object, Reticle> reticles = new LinkedHashMap<>();

    private JPopupMenu popupMenu;

    /**
     * The last width and height of the component that we painted for. If the width or height is
     * different from these values at the start of paint we'll recalculate all the scaling data.
     */
    private double lastWidth, lastHeight;

    /**
     * The last width and height of the image that we painted for. If the width or height is
     * different from these values at the start of paint we'll recalculate all the scaling data.
     */
    private double lastSourceWidth, lastSourceHeight;

    private Location lastUnitsPerPixel;

    /**
     * The width and height of the image after it has been scaled to fit the bounds of the
     * component.
     */
    private int scaledWidth, scaledHeight;

    /**
     * The ratio of scaled width and height to unscaled width and height. scaledWidth * scaleRatioX
     * = sourceWidth. scaleRatioX = sourceWidth / scaledWidth
     */
    private double scaleRatioX, scaleRatioY;

    /**
     * The Camera's units per pixel scaled at the same ratio as the image. That is, each pixel in
     * the scaled image is scaledUnitsPerPixelX wide and scaledUnitsPerPixelY high.
     */
    private double scaledUnitsPerPixelX, scaledUnitsPerPixelY;

    /**
     * The top left position within the component at which the scaled image can be drawn for it to
     * be centered.
     */
    private int imageX, imageY;

    private boolean selectionEnabled;
    /**
     * Rectangle describing the bounds of the selection in image coordinates.
     */
    private Rectangle selection;
    /**
     * The scaled version of the selection Rectangle. Rescaled any time the component's size is
     * changed.
     */
    private Rectangle selectionScaled;
    private SelectionMode selectionMode;
    private HandlePosition selectionActiveHandle;
    private int selectionStartX, selectionStartY;
    private float selectionFlashOpacity;
    private float selectionDashPhase;
    private static float[] selectionDashProfile = new float[] {6f, 6f};
    // 11 is the sum of the dash lengths minus 1.
    private static float selectionDashPhaseStart = 11f;

    private CameraViewSelectionTextDelegate selectionTextDelegate;

    private ScheduledExecutorService scheduledExecutor;

    private Preferences prefs = Preferences.userNodeForPackage(CameraView.class);

    private String text;

    private boolean showImageInfo;

    private List<CameraViewActionListener> actionListeners = new ArrayList<>();

    private CameraViewFilter cameraViewFilter;

    private long flashStartTimeMs;
    private long flashLengthMs = 250;

    private boolean showName = false;
    
    private double zoom = 1d;
    private double zoomIncPerMouseWheelTick = DEFAULT_ZOOM_INCREMENT;
    
    private boolean dragJogging = false;
    private boolean lightToggling = false;

    
    private MouseEvent dragJoggingStart = null;
    private MouseEvent dragJoggingTarget = null;
    private int dragJogHandleSize = 50;
    Color dragJogHandleInactiveColor = new Color(125, 125, 125);
    Color dragJogHandleActiveColor = Color.white;

    private int lightToggleSize = 40;
    Color lightToggleInactiveBgColor = new Color(255, 255, 255, 100);
    Color lightToggleActiveBgColor = new Color(0, 0, 0, 100);
    Color lightToggleInactiveColor = new Color(0, 0, 0, 200);
    Color lightToggleActiveColor = new Color(255, 255, 125, 200);

    long lastFrameReceivedTime = 0;
    MovingAverage fpsAverage = new MovingAverage(24);
    double fps = 0;
    public enum RenderingQuality {
        Low, High, BestScale
    }
    RenderingQuality renderingQuality = RenderingQuality.Low;

    public CameraView() {
        setBackground(Color.black);
        setOpaque(true);

        addMouseListener(mouseListener);
        addMouseMotionListener(mouseMotionListener);
        addComponentListener(componentListener);
        addMouseWheelListener(mouseWheelListener);

        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

        // TODO: Cancel this when it's not being used instead of spinning,
        // or maybe create a real thread and wait().
        scheduledExecutor.scheduleAtFixedRate(new Runnable() {
            public void run() {
                if (selectionEnabled && selection != null) {
                    // Adjust the dash phase so the line marches on the next
                    // paint
                    selectionDashPhase -= 1f;
                    if (selectionDashPhase < 0) {
                        selectionDashPhase = selectionDashPhaseStart;
                    }
                    repaint();
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
    }
    
    private String getReticlePrefKey() {
        return PREF_RETICLE + "." + camera.getId();
    }

    private String getZoomIncrementPrefKey() {
        return PREF_ZOOM_INCREMENT + "." + camera.getId();
    }

    private String getQualityRenderingPrefKey() {
        return PREF_RENDERING_QUALITY + "." + camera.getId();
    }

    public void addActionListener(CameraViewActionListener listener) {
        if (!actionListeners.contains(listener)) {
            actionListeners.add(listener);
        }
    }

    public boolean removeActionListener(CameraViewActionListener listener) {
        return actionListeners.remove(listener);
    }

    public void setCamera(Camera camera) {
        // turn off capture for the camera we are replacing, if any
        if (this.camera != null) {
            this.camera.stopContinuousCapture(this);
        }
        this.camera = camera;
        // load the reticle pref, if any
        try {
            String reticleXml = prefs.get(getReticlePrefKey(), null);
            Reticle reticle = (Reticle) XmlSerialize.deserialize(reticleXml);
            setDefaultReticle(reticle);
        }
        catch (Exception e) {
            Logger.debug("Failed to load camera specific reticle, checking default.");
            try {
                String reticleXml = prefs.get(PREF_RETICLE, null);
                Reticle reticle = (Reticle) XmlSerialize.deserialize(reticleXml);
                setDefaultReticle(reticle);
            }
            catch (Exception e1) {
                Logger.debug("No reticle preference found.");
            }
        }

        // load the zoom increment pref, if any
        zoomIncPerMouseWheelTick = prefs.getDouble(getZoomIncrementPrefKey(), DEFAULT_ZOOM_INCREMENT);
        // load sub.pixel rendering prefs, if any.
        try {
            renderingQuality = RenderingQuality.valueOf(prefs.get(getQualityRenderingPrefKey(), RenderingQuality.Low.toString()));
        }
        catch (Exception e) {
            // ignore errors
        }
        // turn on capture for the new camera
        if (this.camera != null) {
            this.camera.startContinuousCapture(this);
        }
    }

    public Camera getCamera() {
        return camera;
    }

    public void setShowName(boolean showName) {
        this.showName = showName;
    }

    public boolean isShowName() {
        return this.showName;
    }

    public void setDefaultReticle(Reticle reticle) {
        setReticle(DEFAULT_RETICLE_KEY, reticle);

        prefs.put(getReticlePrefKey(), XmlSerialize.serialize(reticle));
        try {
            prefs.flush();
        }
        catch (Exception e) {

        }
    }

    public Reticle getDefaultReticle() {
        return reticles.get(DEFAULT_RETICLE_KEY);
    }

    public void setReticle(Object key, Reticle reticle) {
        if (reticle == null) {
            removeReticle(key);
        }
        else {
            reticles.put(key, reticle);
        }
    }

    public Reticle getReticle(Object key) {
        return reticles.get(key);
    }

    public Reticle removeReticle(Object key) {
        return reticles.remove(key);
    }

    public CameraViewSelectionTextDelegate getSelectionTextDelegate() {
        return selectionTextDelegate;
    }

    public void setSelectionTextDelegate(CameraViewSelectionTextDelegate selectionTextDelegate) {
        this.selectionTextDelegate = selectionTextDelegate;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public double getZoomIncPerMouseWheelTick() {
        return zoomIncPerMouseWheelTick;
    }

    public void setZoomIncPerMouseWheelTick(double zoomIncPerMouseWheelTick) {
        prefs.putDouble(getZoomIncrementPrefKey(), zoomIncPerMouseWheelTick);
        this.zoomIncPerMouseWheelTick = zoomIncPerMouseWheelTick;
    }
    
    public RenderingQuality getRenderingQuality() {
        return renderingQuality;
    }

    public void setRenderingQuality(RenderingQuality renderingQuality) {
        prefs.put(getQualityRenderingPrefKey(), renderingQuality.toString());
        this.renderingQuality = renderingQuality;
        calculateScalingData();
    }

    /**
     * Checks to see if the viewing plane for this camera can be changed to different heights. This is
     * true if the camera's Units Per Pixel is different at two different heights.
     * 
     * @return true if the viewing plane can be changed
     */
    public boolean isViewingPlaneChangable() {
        Location upp1 = camera.getUnitsPerPixel(new Length(0.0, LengthUnit.Millimeters));
        Location upp2 = camera.getUnitsPerPixel(new Length(10.0, LengthUnit.Millimeters));
        return !upp1.equals(upp2);
    }

    /**
     * Causes a short flash in the CameraView to get the user's attention.
     */
    public void flash() {
        flashStartTimeMs = System.currentTimeMillis();
        scheduledExecutor.scheduleAtFixedRate(new Runnable() {
            public void run() {
                if (System.currentTimeMillis() - flashStartTimeMs < flashLengthMs) {
                    repaint();
                }
                else {
                    flashStartTimeMs = 0;
                    throw new RuntimeException();
                }
            }
        }, 0, 30, TimeUnit.MILLISECONDS);
    }

    public void setCameraViewFilter(CameraViewFilter cameraViewFilter) {
        this.cameraViewFilter = cameraViewFilter;
        if (camera.isAutoVisible()) {
            camera.ensureCameraVisible();
        }
    }

    public void showFilteredImage(final BufferedImage filteredImage, final long milliseconds) {
        showFilteredImage(filteredImage, null, milliseconds);
    }

    /**
     * Show an animation of N images instead of the camera image for milliseconds each frame. After 
     * N*milliseconds elapses the view goes back to showing the camera image. See also showFilteredImage().
     * 
     * @param images
     * @param texts
     * @param millseconds
     */
    public void showFilteredImages(BufferedImage [] filteredImages, String [] texts, long milliseconds) {
        setCameraViewFilter(new CameraViewFilter() {
            long t = System.currentTimeMillis();
            int n = filteredImages.length;

            @Override
            public BufferedImage filterCameraImage(Camera camera, BufferedImage image) {
                long elapsed = System.currentTimeMillis() - t;
                if (elapsed < milliseconds*n) {
                    int i = (int) (elapsed/milliseconds);
                    if (texts != null && i < texts.length) {
                        setText(texts[i]);
                    }
                    return filteredImages[i];
                }
                else {
                    if (texts != null) {
                        setText(null);
                    }
                    setCameraViewFilter(null);
                    return image;
                }
            }
        });
        // Make sure the filtered image is shown immediately and also counted as fps (for 0 or low fps cameras). 
        frameReceived(null);
    }

    /**
     * Show image instead of the camera image for milliseconds. After milliseconds elapses the view
     * goes back to showing the camera image. The image should be the same width and height as the
     * camera image otherwise the behavior is undefined. This function is intended to be used to
     * briefly show the result of image processing. This is a shortcut to
     * setCameraViewFilter(CameraViewFilter) which simply removes itself after the specified time.
     * 
     * In addition to showing the given image, if the text parameters is not null the text will be
     * shown during the timeout using setText().
     * 
     * @param image
     * @param text
     * @param millseconds
     */
    public void showFilteredImage(BufferedImage filteredImage, String text, long milliseconds) {
        showFilteredImages(new BufferedImage[] { filteredImage }, text == null ? null : new String [] { text }, milliseconds);
    }

    public BufferedImage captureSelectionImage() {
        if (selection == null || lastFrame == null) {
            return null;
        }

        selectionFlashOpacity = 1.0f;

        ScheduledFuture future = scheduledExecutor.scheduleAtFixedRate(new Runnable() {
            public void run() {
                if (selectionFlashOpacity > 0) {
                    selectionFlashOpacity -= 0.07;
                    selectionFlashOpacity = Math.max(0, selectionFlashOpacity);
                    repaint();
                }
                else {
                    throw new RuntimeException();
                }
            }
        }, 0, 30, TimeUnit.MILLISECONDS);

        int sx = selection.x;
        int sy = selection.y;
        int sw = selection.width;
        int sh = selection.height;

        BufferedImage image = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        Graphics g = image.getGraphics();
        g.drawImage(lastFrame, 0, 0, sw, sh, sx, sy, sx + sw, sy + sh, null);
        g.dispose();

        while (!future.isDone()) {
            
        }

        return image;
    }

    public Rectangle getSelection() {
        return selection;
    }

    @Override
    public void frameReceived(BufferedImage img) {
        if (cameraViewFilter != null) {
            img = cameraViewFilter.filterCameraImage(camera, img);
        }
        if (img == null) {
            return;
        }
        BufferedImage oldFrame = lastFrame;
        lastFrame = img;
        if (oldFrame == null
                || (oldFrame.getWidth() != img.getWidth() || oldFrame.getHeight() != img.getHeight()
                        || !camera.getUnitsPerPixelAtZ().equals(lastUnitsPerPixel))) {
            calculateScalingData();
        }
        fps = 1000.0 / fpsAverage.next(System.currentTimeMillis() - lastFrameReceivedTime);
        lastFrameReceivedTime = System.currentTimeMillis();
        repaint();
    }

    /**
     * Calculates a bunch of scaling data that we cache to speed up painting. This is recalculated
     * when the size of the component or the size of the source changes. This method is
     * synchronized, along with paintComponent() so that the updates to the cached data are atomic.
     */
    private synchronized void calculateScalingData() {
        BufferedImage image = lastFrame;

        if (image == null) {
            return;
        }

        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;

        double destWidth = width, destHeight = height;

        lastWidth = width;
        lastHeight = height;

        lastSourceWidth = image.getWidth();
        lastSourceHeight = image.getHeight();
        
        double heightRatio = lastSourceHeight / destHeight;
        double widthRatio = lastSourceWidth / destWidth;

        if (heightRatio > widthRatio) {
            double aspectRatio = lastSourceWidth / lastSourceHeight;
            scaledHeight = (int) destHeight;
            scaledWidth = (int) (scaledHeight * aspectRatio);
        }
        else {
            double aspectRatio = lastSourceHeight / lastSourceWidth;
            scaledWidth = (int) destWidth;
            scaledHeight = (int) (scaledWidth * aspectRatio);
        }

        scaledWidth *= zoom;
        scaledHeight *= zoom;

        if (renderingQuality == RenderingQuality.BestScale) {
            // Bring to an integral scaling factor.
            double scalingFactor = lastSourceWidth > scaledWidth ? 
                    1./Math.max(1, Math.ceil(lastSourceWidth/scaledWidth))
                    : Math.max(1, Math.floor(scaledWidth/lastSourceWidth));
            scaledWidth = (int)(lastSourceWidth*scalingFactor);
            scaledHeight = (int)(lastSourceHeight*scalingFactor);
        }

        imageX = ins.left + (width / 2) - (scaledWidth / 2);
        imageY = ins.top + (height / 2) - (scaledHeight / 2);

        scaleRatioX = lastSourceWidth / (double) scaledWidth;
        scaleRatioY = lastSourceHeight / (double) scaledHeight;
        
        lastUnitsPerPixel = camera.getUnitsPerPixelAtZ();
        scaledUnitsPerPixelX = lastUnitsPerPixel.getX() * scaleRatioX;
        scaledUnitsPerPixelY = lastUnitsPerPixel.getY() * scaleRatioY;

        if (selectionEnabled && selection != null) {
            // setSelection() handles updating the scaled rectangle
            setSelection(selection);
        }
    }

    @Override
    protected synchronized void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (lastFrame == null) {
            frameReceived(AbstractBroadcastingCamera.getCaptureErrorImage());
        }
        BufferedImage image = lastFrame;
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;
        Graphics2D g2d = (Graphics2D) g;
        g.setColor(getBackground());
        g2d.fillRect(ins.left, ins.top, width, height);
        if (image != null) {
            // Only render if there is a valid image.
            if (renderingQuality == RenderingQuality.Low) {
                g2d.drawImage(lastFrame, imageX, imageY, scaledWidth, scaledHeight, null);
            }
            else {
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                AffineTransform t = new AffineTransform();
                double scaleW = ((double)scaledWidth)/image.getWidth();
                double scaleH = ((double)scaledHeight)/image.getHeight();
                // Scaled
                t.translate(imageX, imageY);
                t.scale(scaleW, scaleH);
                g2d.drawImage(lastFrame, t, null);
            }

            double c = MainFrame.get().getMachineControls().getSelectedTool().getLocation()
                    .getRotation();

            for (Reticle reticle : reticles.values()) {
                reticle.draw(g2d, camera.getUnitsPerPixelAtZ().getUnits(), scaledUnitsPerPixelX,
                        scaledUnitsPerPixelY, ins.left + (width / 2), ins.top + (height / 2),
                        scaledWidth, scaledHeight, c);
            }

            if (text != null) {
                drawTextOverlay(g2d, 10, 10, text);
            }

            if (showName) {
                Dimension dim = measureTextOverlay(g2d, camera.getName());
                drawTextOverlay(g2d, 10, height - dim.height - 10, camera.getName());
            }

            if (showImageInfo && text == null) {
                drawImageInfo(g2d, 10, 10, image);
            }

            if (selectionEnabled && selection != null) {
                paintSelection(g2d);
            }

            if (!selectionEnabled) {
                paintDragJogging(g2d);
                paintLightToggle(g2d);
            }

            if (camera.isUnitsPerPixelAtZCalibrated()) {
                // Display the height of the reticle in the lower left corner if it is lower than
                // safe Z.
                if (camera.getLocation().getLengthZ().compareTo(camera.getSafeZ()) < 0) {   
                    LengthConverter lengthConverter = new LengthConverter();
                    String text = "Z: " + lengthConverter.convertForward(camera.getLocation().getLengthZ());
                    Dimension dim = measureTextOverlay(g2d, text);
                    drawTextOverlay(g2d, width - dim.width - 10, height - dim.height - 10, text);
                }
            }
        }
        else {
            g.setColor(Color.red);
            g.drawLine(ins.left, ins.top, ins.right, ins.bottom);
            g.drawLine(ins.right, ins.top, ins.left, ins.bottom);
        }

        if (flashStartTimeMs > 0) {
            long timeLeft = flashLengthMs - (System.currentTimeMillis() - flashStartTimeMs);
            float alpha = (1f / flashLengthMs) * timeLeft;
            alpha = Math.min(alpha, 1);
            alpha = Math.max(alpha, 0);
            g2d.setColor(new Color(1f, 1f, 1f, alpha));
            g2d.fillRect(0, 0, getWidth(), getHeight());
        }
    }
    
    private boolean isPointInsideRectangle(int pointX, int pointY, int rectX1, int rectY1, int rectX2, int rectY2) {
        return pointX >= rectX1 && pointX <= rectX2 && pointY >= rectY1 && pointY <= rectY2;
    }
    
    private boolean isPointInsideCircle(int pointX, int pointY, int circleX, int circleY, int circleRadius) {
        return Math.pow(pointX - circleX, 2) + Math.pow(pointY - circleY, 2) <= Math.pow(circleRadius, 2);
    }
    
    private boolean isPointInsideDragJogRotationHandle(int x, int y) {
        HeadMountable selectedTool = MainFrame.get().getMachineControls().getSelectedTool(); 
        if (selectedTool.getAxisRotation() == null) {
            return false;
        }
        
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;
        int handleSize = 50;
        int halfHandleSize = handleSize / 2;
        
        // The rotation handle is drawn on an imaginary circle centered in the view
        double rotHandleRadius = Math.min(width, height) / 2 * .80;
        double rotHandleAngle = -Utils2D.normalizeAngle(selectedTool.getLocation().getRotation() + 90);
        double rotHandleX = rotHandleRadius * Math.cos(Math.toRadians(rotHandleAngle)) + (width / 2.);
        double rotHandleY = rotHandleRadius * Math.sin(Math.toRadians(rotHandleAngle)) + (height / 2.);
        
        return isPointInsideCircle(x, y, (int) rotHandleX, (int) rotHandleY, (int) halfHandleSize);
    }
    
    private boolean isPointInsideDragJogXyHandle(int x, int y) {
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;
        int halfHandleSize = dragJogHandleSize / 2;
        
        int xyHandleX0 = width / 2 - halfHandleSize;
        int xyHandleY0 = height / 2 - halfHandleSize;
        int xyHandleX1 = xyHandleX0 + dragJogHandleSize;
        int xyHandleY1 = xyHandleY0 + dragJogHandleSize;
        
        return isPointInsideRectangle(x, y, xyHandleX0, xyHandleY0, xyHandleX1, xyHandleY1);
    }
    
    private boolean isPointInsideDragJogHandle(int x, int y) {
        return isPointInsideDragJogXyHandle(x, y) || isPointInsideDragJogRotationHandle(x, y);
    }

    private boolean isPointInsideLightToggle(int x, int y) {
        Actuator actuator = camera.getLightActuator();
        if (actuator == null) {
            return false;
        }
        
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;
        // Center
        int x0 = width - lightToggleSize/2;
        int y0 = lightToggleSize/2;
        
        double distance = Math.sqrt(Math.pow(x-x0, 2)+ Math.pow(y-y0, 2));

        return distance <= lightToggleSize/2;
    }

    private void drawCircle(Graphics2D g2d, int centerX, int centerY, int radius) {
        g2d.drawArc(centerX - radius, centerY - radius, radius * 2, radius * 2, 0, 360);
    }

    private double snapRotationAngleToTypicalAngles(double angle) {
        // Round to typical pcb placing angles
        return ((int) Math.round(angle / 45)) * 45.0;
    }
    
    private void paintDragJogRotationHandle(Graphics2D g2d, boolean active) {
        HeadMountable selectedTool = MainFrame.get().getMachineControls().getSelectedTool(); 
        if (selectedTool.getAxisRotation() == null) {
            return;
        }
        
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;

        // The rotation handle is drawn on an imaginary circle centered in the view
        double rotHandleRadius = Math.min(width, height) / 2 * .80;
        double rotHandleAngle = -Utils2D.normalizeAngle(selectedTool.getLocation().getRotation() + 90);
        double rotHandleX = rotHandleRadius * Math.cos(Math.toRadians(rotHandleAngle)) + (width / 2.);
        double rotHandleY = rotHandleRadius * Math.sin(Math.toRadians(rotHandleAngle)) + (height / 2.);

        // Draw the circular handle at it's original position in the inactive color
        g2d.setColor(dragJogHandleInactiveColor);
        drawCircle(g2d, (int) rotHandleX, (int) rotHandleY, dragJogHandleSize / 2);

        if (active) {
            // Draw the imaginary circle as a guideline for the rotation handle
            g2d.setColor(dragJogHandleInactiveColor);
            drawCircle(g2d, width / 2, height / 2, (int) rotHandleRadius);
            
            int targetX = dragJoggingTarget.getX();
            int targetY = dragJoggingTarget.getY();
            
            // Now draw the circular handle at it's target position in the active color
            double rotTargetHandleAngle = Math.toDegrees(Math.atan2(targetY - (height / 2), targetX - (width / 2)));
            rotTargetHandleAngle = Utils2D.normalizeAngle(rotTargetHandleAngle);

            // If the alt button is pressed snap to certain angles
            if(dragJoggingTarget.isAltDown()) {
                rotTargetHandleAngle = snapRotationAngleToTypicalAngles(rotTargetHandleAngle);
            }

            double rotTargetHandleX = rotHandleRadius * Math.cos(Math.toRadians(rotTargetHandleAngle)) + (width / 2.);
            double rotTargetHandleY = rotHandleRadius * Math.sin(Math.toRadians(rotTargetHandleAngle)) + (height / 2.);
            g2d.setColor(dragJogHandleActiveColor);
            drawCircle(g2d, (int) rotTargetHandleX, (int) rotTargetHandleY, dragJogHandleSize / 2);
            
            // And draw a rotated crosshair to help the user line things up
            double x1 = rotHandleRadius * Math.cos(Math.toRadians(rotTargetHandleAngle)) + (width / 2.);
            double y1 = rotHandleRadius * Math.sin(Math.toRadians(rotTargetHandleAngle)) + (height / 2.);
            double x2 = rotHandleRadius * Math.cos(Math.toRadians(rotTargetHandleAngle + 180)) + (width / 2.);
            double y2 = rotHandleRadius * Math.sin(Math.toRadians(rotTargetHandleAngle + 180)) + (height / 2.);
            g2d.drawLine((int) x1, (int) y1, (int) x2, (int) y2);
            x1 = rotHandleRadius * Math.cos(Math.toRadians(rotTargetHandleAngle + 90)) + (width / 2.);
            y1 = rotHandleRadius * Math.sin(Math.toRadians(rotTargetHandleAngle + 90)) + (height / 2.);
            x2 = rotHandleRadius * Math.cos(Math.toRadians(rotTargetHandleAngle + 270)) + (width / 2.);
            y2 = rotHandleRadius * Math.sin(Math.toRadians(rotTargetHandleAngle + 270)) + (height / 2.);
            g2d.drawLine((int) x1, (int) y1, (int) x2, (int) y2);
        }
    }
    
    private void paintDragJogXyHandle(Graphics2D g2d, boolean active) {
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;

        int xyHandleX0 = width / 2 - dragJogHandleSize / 2;
        int xyHandleY0 = height / 2 - dragJogHandleSize / 2;

        g2d.setColor(dragJogHandleInactiveColor);
        g2d.drawRect(xyHandleX0, xyHandleY0, dragJogHandleSize, dragJogHandleSize);

        if (active) {
            int targetX = dragJoggingTarget.getX();
            int targetY = dragJoggingTarget.getY();
            
            int xyTargetHandleX0 = targetX - dragJogHandleSize / 2;
            int xyTargetHandleY0 = targetY - dragJogHandleSize / 2;
            g2d.setColor(dragJogHandleActiveColor);
            g2d.drawRect(xyTargetHandleX0, xyTargetHandleY0, dragJogHandleSize, dragJogHandleSize);
            
            g2d.drawLine(xyTargetHandleX0 + dragJogHandleSize / 2, xyTargetHandleY0,
                    xyTargetHandleX0 + dragJogHandleSize / 2, xyTargetHandleY0 + dragJogHandleSize);
            g2d.drawLine(xyTargetHandleX0, xyTargetHandleY0 + dragJogHandleSize / 2,
                    xyTargetHandleX0 + dragJogHandleSize, xyTargetHandleY0 + dragJogHandleSize / 2);
            
            g2d.drawLine(xyHandleX0 + dragJogHandleSize / 2, 
                    xyHandleY0 + dragJogHandleSize / 2,
                    targetX, targetY);
        }
    }
    
    private void paintDragJogging(Graphics2D g2d) {
        paintDragJogXyHandle(g2d, isDragJogging() 
                && !isPointInsideDragJogRotationHandle(dragJoggingStart.getX(), dragJoggingStart.getY()));
        paintDragJogRotationHandle(g2d, isDragJogging() 
                && isPointInsideDragJogRotationHandle(dragJoggingStart.getX(), dragJoggingStart.getY()));
    }

    private void paintLightToggle(Graphics2D g2d) {
        Actuator actuator = camera.getLightActuator();
        if (actuator == null) {
            return;
        }
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;
        // Center
        int x0 = width - lightToggleSize/2;
        int y0 = lightToggleSize/2;
        if (isTogglingLight()) {
            x0++;
            y0++;
        }
        boolean active = actuator.isActuated() != null && actuator.isActuated();
        for (int pass = 0 ; pass < 2; pass++) {
            if (pass == 0) {
                g2d.setStroke(new BasicStroke(active ? 4 : 4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2d.setColor(active ? lightToggleActiveBgColor : lightToggleInactiveBgColor);
            }
            else {
                g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2d.setColor(active ? lightToggleActiveColor : lightToggleInactiveColor);
            }
            drawCircle(g2d, x0, y0, (int)(lightToggleSize*0.27));
            double r0 = lightToggleSize*0.36;
            double r1 = lightToggleSize*0.5-1;
            for (int angle = 15; angle < 360; angle += 30) {
                g2d.drawLine(
                        (int)(x0 + 0.5 + r0*Math.cos(Math.toRadians(angle))), 
                        (int)(y0 + 0.5 + r0*Math.sin(Math.toRadians(angle))),
                        (int)(x0 + 0.5 + r1*Math.cos(Math.toRadians(angle))), 
                        (int)(y0 + 0.5 + r1*Math.sin(Math.toRadians(angle))));
            }
        }
    }

    private void paintSelection(Graphics2D g2d) {
        int rx = selectionScaled.x;
        int ry = selectionScaled.y;
        int rw = selectionScaled.width;
        int rh = selectionScaled.height;
        int rx2 = rx + rw;
        int ry2 = ry + rh;
        int rxc = rx + rw / 2;
        int ryc = ry + rh / 2;

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Draw the dashed rectangle, black background, white dashes
        g2d.setColor(Color.black);
        g2d.setStroke(new BasicStroke(1f));
        g2d.drawRect(rx, ry, rw, rh);
        g2d.setColor(Color.white);
        g2d.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0,
                selectionDashProfile, selectionDashPhase));
        g2d.drawRect(rx, ry, rw, rh);

        if (selectionMode != SelectionMode.Creating) {
            // If we're not drawing a whole new rectangle, draw the
            // handles for the existing one.
            drawHandle(g2d, rx, ry);
            drawHandle(g2d, rx2, ry);
            drawHandle(g2d, rx, ry2);
            drawHandle(g2d, rx2, ry2);

            drawHandle(g2d, rxc, ry);
            drawHandle(g2d, rx2, ryc);
            drawHandle(g2d, rxc, ry2);
            drawHandle(g2d, rx, ryc);
        }

        if (selectionTextDelegate != null) {
            String text = selectionTextDelegate.getSelectionText(this);
            if (text != null) {
                drawTextOverlay(g2d, (int) (rx + rw + 6), (int) (ry + rh + 6), text);
            }
        }

        if (selectionFlashOpacity > 0) {
            g2d.setColor(new Color(1.0f, 1.0f, 1.0f, selectionFlashOpacity));
            g2d.fillRect(rx, ry, rw, rh);
        }
    }

    /**
     * Draws a standard handle centered on the given x and y position.
     * 
     * @param g2d
     * @param x
     * @param y
     */
    private static void drawHandle(Graphics2D g2d, int x, int y) {
        g2d.setStroke(new BasicStroke(1f));
        g2d.setColor(new Color(153, 153, 187));
        g2d.fillArc(x - HANDLE_DIAMETER / 2, y - HANDLE_DIAMETER / 2, HANDLE_DIAMETER,
                HANDLE_DIAMETER, 0, 360);
        g2d.setColor(Color.white);
        g2d.drawArc(x - HANDLE_DIAMETER / 2, y - HANDLE_DIAMETER / 2, HANDLE_DIAMETER,
                HANDLE_DIAMETER, 0, 360);
    }

    /**
     * Gets the HandlePosition, if any, at the given x and y. Returns null if there is not a handle
     * at that position.
     * 
     * @param x
     * @param y
     * @return
     */
    private HandlePosition getSelectionHandleAtPosition(int x, int y) {
        if (selection == null) {
            return null;
        }

        int rx = selectionScaled.x;
        int ry = selectionScaled.y;
        int rw = selectionScaled.width;
        int rh = selectionScaled.height;
        int rx2 = rx + rw;
        int ry2 = ry + rh;
        int rxc = rx + rw / 2;
        int ryc = ry + rh / 2;

        if (isWithinHandle(x, y, rx, ry)) {
            return HandlePosition.NW;
        }
        else if (isWithinHandle(x, y, rx2, ry)) {
            return HandlePosition.NE;
        }
        else if (isWithinHandle(x, y, rx, ry2)) {
            return HandlePosition.SW;
        }
        else if (isWithinHandle(x, y, rx2, ry2)) {
            return HandlePosition.SE;
        }
        else if (isWithinHandle(x, y, rxc, ry)) {
            return HandlePosition.N;
        }
        else if (isWithinHandle(x, y, rx2, ryc)) {
            return HandlePosition.E;
        }
        else if (isWithinHandle(x, y, rxc, ry2)) {
            return HandlePosition.S;
        }
        else if (isWithinHandle(x, y, rx, ryc)) {
            return HandlePosition.W;
        }
        return null;
    }

    /**
     * A specialization of isWithin() that uses uses the bounding box of a handle.
     * 
     * @param x
     * @param y
     * @param handleX
     * @param handleY
     * @return
     */
    private static boolean isWithinHandle(int x, int y, int handleX, int handleY) {
        return isWithin(x, y, handleX - 4, handleY - 4, 8, 8);
    }

    private static boolean isWithin(int pointX, int pointY, int boundsX, int boundsY,
            int boundsWidth, int boundsHeight) {
        return pointX >= boundsX && pointX <= (boundsX + boundsWidth) && pointY >= boundsY
                && pointY <= (boundsY + boundsHeight);
    }

    private static Rectangle normalizeRectangle(Rectangle r) {
        return normalizeRectangle(r.x, r.y, r.width, r.height);
    }

    /**
     * Builds a rectangle with the given parameters. If the width or height is negative the
     * corresponding x or y value is modified and the width or height is made positive.
     * 
     * @param x
     * @param y
     * @param width
     * @param height
     * @return
     */
    private static Rectangle normalizeRectangle(int x, int y, int width, int height) {
        if (width < 0) {
            width *= -1;
            x -= width;
        }
        if (height < 0) {
            height *= -1;
            y -= height;
        }
        return new Rectangle(x, y, width, height);
    }

    /**
     * Draws text in a nice bubble at the given position. Newline characters in the text cause line
     * breaks.
     * 
     * @param g2d
     * @param topLeftX
     * @param topLeftY
     * @param text
     */
    private static void drawTextOverlay(Graphics2D g2d, int topLeftX, int topLeftY, String text) {
        Insets insets = new Insets(10, 10, 10, 10);
        int interLineSpacing = 4;
        int cornerRadius = 8;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.setFont(g2d.getFont().deriveFont(12.0f));
        String[] lines = text.split("\n");
        List<TextLayout> textLayouts = new ArrayList<>();
        int textWidth = 0, textHeight = 0;
        for (String line : lines) {
            TextLayout textLayout = new TextLayout(line, g2d.getFont(), g2d.getFontRenderContext());
            textWidth = (int) Math.max(textWidth, textLayout.getBounds().getWidth());
            textHeight += (int) textLayout.getBounds().getHeight() + interLineSpacing;
            textLayouts.add(textLayout);
        }
        textHeight -= interLineSpacing;
        g2d.setColor(new Color(0, 0, 0, 0.75f));
        g2d.fillRoundRect(topLeftX, topLeftY, textWidth + insets.left + insets.right,
                textHeight + insets.top + insets.bottom, cornerRadius, cornerRadius);
        g2d.setColor(Color.white);
        g2d.drawRoundRect(topLeftX, topLeftY, textWidth + insets.left + insets.right,
                textHeight + insets.top + insets.bottom, cornerRadius, cornerRadius);
        int yPen = topLeftY + insets.top;
        for (TextLayout textLayout : textLayouts) {
            yPen += textLayout.getBounds().getHeight();
            textLayout.draw(g2d, topLeftX + insets.left, yPen);
            yPen += interLineSpacing;
        }
    }

    private static Dimension measureTextOverlay(Graphics2D g2d, String text) {
        Insets insets = new Insets(10, 10, 10, 10);
        int interLineSpacing = 4;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.setFont(g2d.getFont().deriveFont(12.0f));
        String[] lines = text.split("\n");
        List<TextLayout> textLayouts = new ArrayList<>();
        int textWidth = 0, textHeight = 0;
        for (String line : lines) {
            TextLayout textLayout = new TextLayout(line, g2d.getFont(), g2d.getFontRenderContext());
            textWidth = (int) Math.max(textWidth, textLayout.getBounds().getWidth());
            textHeight += (int) textLayout.getBounds().getHeight() + interLineSpacing;
            textLayouts.add(textLayout);
        }
        textHeight -= interLineSpacing;
        return new Dimension(textWidth + insets.left + insets.right,
                textHeight + insets.top + insets.bottom);
    }

    private void drawImageInfo(Graphics2D g2d, int topLeftX, int topLeftY,
            BufferedImage image) {
        if (image == null) {
            return;
        }
        String text = String.format("Resolution: %d x %d\nZoom: %d%%\nFPS: %.1f\nHistogram:", 
                image.getWidth(),
                image.getHeight(), 
                (int) (zoom * 100),
                fps);
        Insets insets = new Insets(10, 10, 10, 10);
        int interLineSpacing = 4;
        int cornerRadius = 8;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.setFont(g2d.getFont().deriveFont(12.0f));
        String[] lines = text.split("\n");
        List<TextLayout> textLayouts = new ArrayList<>();
        int textWidth = 0, textHeight = 0;
        for (String line : lines) {
            TextLayout textLayout = new TextLayout(line, g2d.getFont(), g2d.getFontRenderContext());
            textWidth = (int) Math.max(textWidth, textLayout.getBounds().getWidth());
            textHeight += (int) textLayout.getBounds().getHeight() + interLineSpacing;
            textLayouts.add(textLayout);
        }
        textHeight -= interLineSpacing;

        int histogramHeight = 50 + 2;
        int histogramWidth = 255 + 2;

        int width = Math.max(textWidth, histogramWidth);
        int height = textHeight + histogramHeight;

        g2d.setColor(new Color(0, 0, 0, 0.75f));
        g2d.fillRoundRect(topLeftX, topLeftY, width + insets.left + insets.right,
                height + insets.top + insets.bottom, cornerRadius, cornerRadius);
        g2d.setColor(Color.white);
        g2d.drawRoundRect(topLeftX, topLeftY, width + insets.left + insets.right,
                height + insets.top + insets.bottom, cornerRadius, cornerRadius);
        int yPen = topLeftY + insets.top;
        for (TextLayout textLayout : textLayouts) {
            yPen += textLayout.getBounds().getHeight();
            textLayout.draw(g2d, topLeftX + insets.left, yPen);
            yPen += interLineSpacing;
        }

        g2d.setColor(new Color(1, 1, 1, 0.20f));
        g2d.fillRect(topLeftX + insets.left, yPen, histogramWidth, histogramHeight);

        // Calculate the histogram
        long[][] histogram = VisionUtils.computeImageHistogram(image);
        // find the highest value in the histogram
        long maxVal = 0;
        for (int channel = 0; channel < 3; channel++) {
            // Smooth it 
            long lastBucket = histogram[channel][0];
            for (int bucket = 1; bucket < 255; bucket++) {
                long currentBucket = histogram[channel][bucket];
                histogram[channel][bucket] = 
                        (currentBucket*2
                                + lastBucket
                                + histogram[channel][bucket+1])/4;
                lastBucket = currentBucket;
            }
            long [] sorted = histogram[channel].clone();
            Arrays.sort(sorted);
            // Exclude the largest two extremes, typically saturation values. 
            maxVal = Math.max(maxVal, sorted[sorted.length-1-2]);
        }
        // and scale it to 50 pixels tall
        double scale = 50.0 / maxVal;
        Color[] colors = new Color[] {new Color(255, 0, 0), new Color(0, 255, 0), new Color(0, 0, 255)};
        for (int bucket = 0; bucket < 256; bucket++) {
            // Rank the three colors, we want to draw overlapping histogram bars with mixed colors.
            TreeMap<Double, Integer> map = new TreeMap<>();
            map.put(histogram[0][bucket]+0.1, 0);
            map.put(histogram[1][bucket]+0.2, 1);
            map.put(histogram[2][bucket]+0.3, 2);
            // Start with white, where all three colors overlap.
            Color color = new Color(255, 255, 255);
            int value0 = 0;
            for (Entry<Double, Integer> entry : map.entrySet()) {
                g2d.setColor(color);
                int value1 = (int) Math.min(50, (entry.getKey() * scale) + 0.5);
                if (value1 > value0) {
                    g2d.drawLine(topLeftX + insets.left + 1 + bucket, yPen + 1 + 50 - value0 - 1,
                        topLeftX + insets.left + 1 + bucket, yPen + 1 + 50 - value1);
                }
                value0 = value1;
                // Subtract the color that is out.
                color = new Color(
                        color.getRed() - colors[entry.getValue()].getRed(), 
                        color.getGreen() - colors[entry.getValue()].getGreen(),
                        color.getBlue() - colors[entry.getValue()].getBlue());
            }
        }
    }

    /**
     * Changes the HandlePosition to it's inverse if the given rectangle has a negative width,
     * height or both.
     * 
     * @param r
     */
    private static HandlePosition getOpposingHandle(Rectangle r, HandlePosition handlePosition) {
        if (r.getWidth() < 0 && r.getHeight() < 0) {
            if (handlePosition == HandlePosition.NW) {
                return HandlePosition.SE;
            }
            else if (handlePosition == HandlePosition.NE) {
                return HandlePosition.SW;
            }
            else if (handlePosition == HandlePosition.SE) {
                return HandlePosition.NW;
            }
            else if (handlePosition == HandlePosition.SW) {
                return HandlePosition.NE;
            }
        }
        else if (r.getWidth() < 0) {
            if (handlePosition == HandlePosition.NW) {
                return HandlePosition.NE;
            }
            else if (handlePosition == HandlePosition.NE) {
                return HandlePosition.NW;
            }
            else if (handlePosition == HandlePosition.SE) {
                return HandlePosition.SW;
            }
            else if (handlePosition == HandlePosition.SW) {
                return HandlePosition.SE;
            }
            else if (handlePosition == HandlePosition.E) {
                return HandlePosition.W;
            }
            else if (handlePosition == HandlePosition.W) {
                return HandlePosition.E;
            }
        }
        else if (r.getHeight() < 0) {
            if (handlePosition == HandlePosition.SW) {
                return HandlePosition.NW;
            }
            else if (handlePosition == HandlePosition.SE) {
                return HandlePosition.NE;
            }
            else if (handlePosition == HandlePosition.NW) {
                return HandlePosition.SW;
            }
            else if (handlePosition == HandlePosition.NE) {
                return HandlePosition.SE;
            }
            else if (handlePosition == HandlePosition.S) {
                return HandlePosition.N;
            }
            else if (handlePosition == HandlePosition.N) {
                return HandlePosition.S;
            }
        }
        return handlePosition;
    }

    /**
     * Set the selection rectangle in image coordinates.
     * 
     * @param x
     * @param y
     * @param width
     * @param height
     */
    public void setSelection(int x, int y, int width, int height) {
        setSelection(new Rectangle(x, y, width, height));
    }

    /**
     * Set the selection rectangle in image coordinates.
     * 
     * @param r
     */
    public void setSelection(Rectangle r) {
        if (r == null) {
            selection = null;
            selectionMode = null;
        }
        else {
            selectionActiveHandle = getOpposingHandle(r, selectionActiveHandle);
            selection = normalizeRectangle(r);

            int rx = (int) (imageX + selection.x / scaleRatioX);
            int ry = (int) (imageY + selection.y / scaleRatioY);
            int rw = (int) (selection.width / scaleRatioX);
            int rh = (int) (selection.height / scaleRatioY);
            selectionScaled = new Rectangle(rx, ry, rw, rh);
        }
    }

    /**
     * Set the selection rectangle in component coordinates. Updates the selection property with the
     * properly scaled coordinates.
     * 
     * @param x
     * @param y
     * @param width
     * @param height
     */
    private void setScaledSelection(int x, int y, int width, int height) {
        selectionScaled = new Rectangle(x, y, width, height);
        selectionActiveHandle = getOpposingHandle(selectionScaled, selectionActiveHandle);
        selectionScaled = normalizeRectangle(selectionScaled);

        int rx = (int) ((x - imageX) * scaleRatioX);
        int ry = (int) ((y - imageY) * scaleRatioY);
        int rw = (int) (width * scaleRatioX);
        int rh = (int) (height * scaleRatioY);

        selection = new Rectangle(rx, ry, rw, rh);
    }

    public boolean isSelectionEnabled() {
        return selectionEnabled;
    }

    public void setSelectionEnabled(boolean selectionEnabled) {
        this.selectionEnabled = selectionEnabled;
    }

    public boolean isShowImageInfo() {
        return showImageInfo;
    }

    public void setShowImageInfo(boolean showImageInfo) {
        this.showImageInfo = showImageInfo;
    }

    public static Cursor getCursorForHandlePosition(HandlePosition handlePosition) {
        switch (handlePosition) {
            case NW:
                return Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
            case N:
                return Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
            case NE:
                return Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
            case E:
                return Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
            case SE:
                return Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR);
            case S:
                return Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR);
            case SW:
                return Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR);
            case W:
                return Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
        }
        return null;
    }

    /**
     * Updates the Cursor to reflect the current state of the component.
     */
    private void updateCursor() {
        if (selectionEnabled) {
            if (selectionMode == SelectionMode.Moving) {
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }
            else if (selectionMode == SelectionMode.Resizing) {
                setCursor(getCursorForHandlePosition(selectionActiveHandle));
            }
            else if (selectionMode == null && selection != null) {
                Point p = getMousePosition();
                if (p == null) {
                    return;
                }
                int x = p.x;
                int y = p.y;

                HandlePosition handlePosition = getSelectionHandleAtPosition(x, y);
                if (handlePosition != null) {
                    setCursor(getCursorForHandlePosition(handlePosition));
                }
                else if (selectionScaled.contains(x, y)) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
                else {
                    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                }
            }
            else {
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            }
        }
        else {
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        }
    }

    /**
     * Capture the current image (unscaled, unmodified) and write it to disk.
     */
    private void captureSnapshot() {
        UiUtils.submitUiMachineTask(() -> {
            File dir = new File(Configuration.get().getConfigurationDirectory(), "snapshots");
            dir.mkdirs();
            DateFormat df = new SimpleDateFormat("YYYY-MM-dd_HH.mm.ss.SSS");
            File file = new File(dir, camera.getName() + "_" + df.format(new Date()) + ".png");
            ImageIO.write(camera.lightSettleAndCapture(), "png", file);
            flash();
        });
    }

    private void fireActionEvent(MouseEvent e) {
        if (actionListeners.isEmpty()) {
            return;
        }

        int x = e.getX();
        int y = e.getY();

        // Find the difference in X and Y from the center of the image
        // to the mouse click.
        double offsetX = (scaledWidth / 2.0D) - (x - imageX);
        double offsetY = (scaledHeight / 2.0D) - (y - imageY);

        // Invert the X so that the offsets represent a bottom left to
        // top right coordinate system.
        offsetX = -offsetX;

        // Scale the offsets by the units per pixel for the camera.
        offsetX *= scaledUnitsPerPixelX;
        offsetY *= scaledUnitsPerPixelY;

        // The offsets now represent the distance to move the camera
        // in the Camera's units per pixel's units.

        // Create a location in the Camera's units per pixel's units
        // and with the values of the offsets.
        Location offsets = camera.getUnitsPerPixelAtZ().
                derive(offsetX, offsetY, 0.0, 0.0);
        // Add the offsets to the Camera's position.
        Nozzle nozzle = MainFrame.get().getMachineControls().getSelectedNozzle();
        Location location = camera.getLocation(nozzle).add(offsets);
        CameraViewActionEvent action =
                new CameraViewActionEvent(CameraView.this, e.getX(), e.getY(),
                        e.getX() * scaledUnitsPerPixelX, e.getY() * scaledUnitsPerPixelY, location);
        for (CameraViewActionListener listener : new ArrayList<>(actionListeners)) {
            listener.actionPerformed(action);
        }
    }

    /**
     * Gets the offset from the center of the image in camera pixels given a set of coordinates in
     * the camera view component.
     *  
     * @param x - the x position in the camera's view component as returned by a mouse click
     * @param y - the y position in the camera's view component as returned by a mouse click
     * @return the offset in camera pixels from the center of the image in a bottom left to
     * top right coordinate system
     */
    public Point getCameraViewCenterPixelsFromXy(int x, int y) {
        // Find the difference in X and Y from the center of the image
        // to the mouse click.
        double offsetX = (scaledWidth / 2.0D) - (x - imageX);
        double offsetY = (scaledHeight / 2.0D) - (y - imageY) + 1;

        // Invert the X so that the offsets represent a bottom left to
        // top right coordinate system.
        offsetX = -offsetX;

        return new Point((int) Math.round(offsetX*scaleRatioX),
                (int) Math.round(offsetY*scaleRatioY));
    }

    public Location getCameraViewCenterOffsetsFromXy(int x, int y) {
        // Find the difference in X and Y from the center of the image
        // to the mouse click.
        double offsetX = (scaledWidth / 2.0D) - (x - imageX);
        double offsetY = (scaledHeight / 2.0D) - (y - imageY) + 1;

        // Invert the X so that the offsets represent a bottom left to
        // top right coordinate system.
        offsetX = -offsetX;

        // Scale the offsets by the units per pixel for the camera.
        offsetX *= scaledUnitsPerPixelX;
        offsetY *= scaledUnitsPerPixelY;

        // The offsets now represent the distance in the Camera's units per pixel's units.

        // Create a location in the Camera's units per pixel's units
        // and with the values of the offsets.
        Location offsets = new Location(camera.getUnitsPerPixel().getUnits(), offsetX, offsetY, 0.0, 0.0);
        return offsets;
    }

    private void moveToClick(MouseEvent e) {
        // Get the offset from the Camera view center in Camera's units.
        Location offsets = getCameraViewCenterOffsetsFromXy(e.getX(), e.getY());
        // And move there.
        UiUtils.submitUiMachineTask(() -> {
            // For non-movable cameras, move the nozzle so that the clicked position is centered in
            // the camera's view.  For movable cameras, move the camera so that the clicked position
            // is centered in the camera's view.
            if (camera.getHead() == null) {
                // The camera is non-movable
                // Get the selected nozzle
                Nozzle nozzle = MainFrame.get().getMachineControls().getSelectedNozzle();
                Location currentLocation = nozzle.getLocation();
                // Subtract the offsets from the nozzle position.
                Location location = currentLocation.subtract(offsets);
                // Only change X/Y. 
                location = currentLocation.derive(location, true, true, false, false);
                // Move the nozzle such that the clicked position is moved to the center of the camera view
                if (currentLocation.getLinearLengthTo(camera.getLocation()).compareTo(camera.getRoamingRadius()) < 0
                        && location.getLinearLengthTo(camera.getLocation()).compareTo(camera.getRoamingRadius()) < 0) {
                    // Within the roaming area, no need to go to Safe Z.
                    nozzle.moveTo(location, Motion.MotionOption.JogMotion);
                }
                else {
                    // Current or new location outside roaming area. Move to safe Z.
                    MovableUtils.moveToLocationAtSafeZ(nozzle, location);
                }
            }
            else { 
                // The camera is movable
                // Add the offsets to the Camera's position.
                Location location = camera.getLocation().add(offsets);
                // move the camera to the location
                MovableUtils.moveToLocationAtSafeZ(camera, location);
            }
            MovableUtils.fireTargetedUserAction(camera, true);
        });
    }
    
    private void rotateToClick(MouseEvent e) {
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;

        double rotTargetHandleAngle = Math.toDegrees(Math.atan2(e.getY() - (height / 2), e.getX() - (width / 2)));
        rotTargetHandleAngle = Utils2D.normalizeAngle(rotTargetHandleAngle);

        // If the alt button is pressed snap to certain angles
        if(e.isAltDown()) {
            rotTargetHandleAngle = snapRotationAngleToTypicalAngles(rotTargetHandleAngle);
        }

        double targetAngle = Utils2D.normalizeAngle(-(rotTargetHandleAngle + 90));
        HeadMountable selectedTool = MainFrame.get().getMachineControls().getSelectedTool();

        UiUtils.submitUiMachineTask(() -> {
            Location location = selectedTool.getLocation();
            location = location.derive(null, null, null, targetAngle);
            MovableUtils.moveToLocationAtSafeZ(selectedTool, location);
            MovableUtils.fireTargetedUserAction(selectedTool, true);
        });
    }

    private void toggleLight(MouseEvent e) {
        Actuator actuator = camera.getLightActuator();
        if (actuator == null) {
            return;
        }
        UiUtils.submitUiMachineTask(() -> {
            // Pass as Object for the generic behavior according to the actuator valueType.  
            boolean toggleState = actuator.isActuated() == null || !actuator.isActuated();
            actuator.actuate(toggleState);
            // Note, we cannot use MovableUtils.fireTargetedUserAction(), because this itself might 
            // turn the light on.
            camera.settleAndCapture();
        });
    }

    private void beginSelection(MouseEvent e) {
        // If we're not doing anything currently, we can start
        // a new operation.
        if (selectionMode == null) {
            int x = e.getX();
            int y = e.getY();

            // See if there is a handle under the cursor.
            HandlePosition handlePosition = getSelectionHandleAtPosition(x, y);
            if (handlePosition != null) {
                selectionMode = SelectionMode.Resizing;
                selectionActiveHandle = handlePosition;
            }
            // If not, perhaps they want to move the rectangle
            else if (selection != null && selectionScaled.contains(x, y)) {

                selectionMode = SelectionMode.Moving;
                // Store the distance between the rectangle's origin and
                // where they started moving it from.
                selectionStartX = x - selectionScaled.x;
                selectionStartY = y - selectionScaled.y;
            }
            // If not those, it's time to create a rectangle
            else {
                selectionMode = SelectionMode.Creating;
                selectionStartX = x;
                selectionStartY = y;
            }
        }
    }

    private void continueSelection(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();

        if (selectionMode == SelectionMode.Resizing) {
            int rx = selectionScaled.x;
            int ry = selectionScaled.y;
            int rw = selectionScaled.width;
            int rh = selectionScaled.height;

            if (selectionActiveHandle == HandlePosition.NW) {
                setScaledSelection(x, y, (rw - (x - rx)), (rh - (y - ry)));
            }
            else if (selectionActiveHandle == HandlePosition.NE) {
                setScaledSelection(rx, y, x - rx, (rh - (y - ry)));
            }
            else if (selectionActiveHandle == HandlePosition.N) {
                setScaledSelection(rx, y, rw, (rh - (y - ry)));
            }
            else if (selectionActiveHandle == HandlePosition.E) {
                setScaledSelection(rx, ry, rw + (x - (rx + rw)), rh);
            }
            else if (selectionActiveHandle == HandlePosition.SE) {
                setScaledSelection(rx, ry, rw + (x - (rx + rw)), rh + (y - (ry + rh)));
            }
            else if (selectionActiveHandle == HandlePosition.S) {
                setScaledSelection(rx, ry, rw, rh + (y - (ry + rh)));
            }
            else if (selectionActiveHandle == HandlePosition.SW) {
                setScaledSelection(x, ry, (rw - (x - rx)), rh + (y - (ry + rh)));
            }
            else if (selectionActiveHandle == HandlePosition.W) {
                setScaledSelection(x, ry, (rw - (x - rx)), rh);
            }
        }
        else if (selectionMode == SelectionMode.Moving) {
            setScaledSelection(x - selectionStartX, y - selectionStartY, selectionScaled.width,
                    selectionScaled.height);
        }
        else if (selectionMode == SelectionMode.Creating) {
            int sx = selectionStartX;
            int sy = selectionStartY;
            int w = x - sx;
            int h = y - sy;
            setScaledSelection(sx, sy, w, h);
        }
        updateCursor();
        repaint();
    }

    private void endSelection() {
        selectionMode = null;
        selectionActiveHandle = null;
    }
    
    private void dragJoggingBegin(MouseEvent e) {
        if (! isTogglingLight()) {
            this.dragJogging = true;
            this.dragJoggingStart = e;
            this.dragJoggingTarget = e;
            repaint();
        }
    }
    
    private void dragJoggingContinue(MouseEvent e) {
        if (! isTogglingLight()) {
            this.dragJoggingTarget = e;
            repaint();
        }
    }
    
    private void dragJoggingEnd(MouseEvent e) {
        if (! isTogglingLight()) {
            int startX = dragJoggingStart.getX();
            int startY = dragJoggingStart.getY();
            
            this.dragJogging = false;
            this.dragJoggingStart = null;
            this.dragJoggingTarget = null;
            repaint();
            
            if (isPointInsideDragJogRotationHandle(startX, startY)) {
                rotateToClick(e);
            }
            else {
                moveToClick(e);
            }
        }
    }

    private boolean isDragJogging() {
        return this.dragJogging;
    }

    private void beginTogglingLight() {
        lightToggling = true;
        repaint();
    }
    private void endTogglingLight() {
        lightToggling = false;
        repaint();
    }
    private boolean isTogglingLight() {
        return lightToggling;
    }


    private MouseListener mouseListener = new MouseAdapter() {
 
        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.isPopupTrigger() || e.isShiftDown() || SwingUtilities.isRightMouseButton(e)) {
                return;
            }
            // double click captures an image from the camera and writes it to disk.
            if (e.getClickCount() == 2) {
                captureSnapshot();
            }
            else {
                fireActionEvent(e);
            }
        }

        @Override
        public void mousePressed(MouseEvent e) {
            if (e.isPopupTrigger()) {
                new CameraViewPopupMenu(CameraView.this).show(e.getComponent(), e.getX(), e.getY());
                return;
            }
            else if (e.isShiftDown()) {
                moveToClick(e);
            }
            else if (isPointInsideLightToggle(e.getX(), e.getY())) {
                beginTogglingLight();
            }
            else if (selectionEnabled) {
                beginSelection(e);
            }
        }
        @Override
        public void mouseReleased(MouseEvent e) {
            if (e.isPopupTrigger()) {
                new CameraViewPopupMenu(CameraView.this).show(e.getComponent(), e.getX(), e.getY());
                return;
            }
            else if (isDragJogging()) {
                dragJoggingEnd(e);
            }
            else if (isTogglingLight() && isPointInsideLightToggle(e.getX(), e.getY())) {
                toggleLight(e);
            }
            else {
                endSelection();
            }
            endTogglingLight();
        }
    };

    private MouseMotionListener mouseMotionListener = new MouseMotionAdapter() {
        @Override
        public void mouseMoved(MouseEvent e) {
            updateCursor();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (selectionEnabled) {
                continueSelection(e);
            }
            else if (!isDragJogging()) {
                dragJoggingBegin(e);
            }
            else if (isDragJogging()) {
                dragJoggingContinue(e);
            }
        }
    };

    private ComponentListener componentListener = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
            calculateScalingData();
        }
    };

    private MouseWheelListener mouseWheelListener = new MouseWheelListener() {
        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            int modifiers = e.getModifiersEx();
            boolean ctrlDown = (modifiers & InputEvent.CTRL_DOWN_MASK) != 0;
            if (!ctrlDown) { // Scroll wheel without Ctrl changes the zoom factor
                double zoomInc = Math.max(zoomIncPerMouseWheelTick,
                        // When best-scale is selected, we can only zoom by 1.0 or faster.
                        renderingQuality == RenderingQuality.BestScale ? 1.0 : 0);
                zoom = (Math.round(zoom/zoomInc) - e.getPreciseWheelRotation()) * zoomInc; 
                zoom = Math.max(zoom, 1.0d);
                zoom = Math.min(zoom, 100d);
            }
            calculateScalingData();
            repaint();
        }
    };

    public CameraViewSelectionTextDelegate pixelsAndUnitsTextSelectionDelegate =
            new CameraViewSelectionTextDelegate() {
                @Override
                public String getSelectionText(CameraView cameraView) {
                    Location upp = camera.getUnitsPerPixelAtZ();
                    double widthInUnits = selection.width * upp.getX();
                    double heightInUnits = selection.height * upp.getY();

                    String text = String.format(Locale.US, "%dpx, %dpx\n%2.3f%s, %2.3f%s",
                            (int) selection.getWidth(), (int) selection.getHeight(), widthInUnits,
                            upp.getUnits().getShortName(), heightInUnits,
                            upp.getUnits().getShortName());
                    return text;
                }
            };
            
    // From https://stackoverflow.com/questions/3793400/is-there-a-function-in-java-to-get-moving-average/42407811#42407811
    static class MovingAverage {
        private long [] window;
        private int n, insert;
        private long sum;

        public MovingAverage(int size) {
            window = new long[size];
            insert = 0;
            sum = 0;
        }

        public double next(long val) {
            if (n < window.length)  {
                n++;
            }
            sum -= window[insert];
            sum += val;
            window[insert] = val;
            insert = (insert + 1) % window.length;
            return (double)sum / n;
        }
    }            
}
