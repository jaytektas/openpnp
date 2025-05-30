/*
 * Copyright (C) 2020,2024 Ian Jamison <ian.dev@arkver.com>
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

package org.openpnp.machine.reference.camera;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.freedesktop.gstreamer.Structure;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.FlowReturn;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Bin;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Sample;

import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.camera.wizards.GstreamerCameraConfigurationWizard;
import org.openpnp.spi.PropertySheetHolder;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;

/**
 * A Camera implementation based on an arbitrary gst_parse_launch pipeline.
 */
public class GstreamerCamera extends ReferenceCamera {
    static {
        Gst.init();
    }

    @Attribute(name = "gstPipeline", required = true)
    private String gstPipeString;

    private BufferedImage currentImage;
    private AppSink videosink;
    private Pipeline pipe;
    private AtomicBoolean newFrame = new AtomicBoolean();

    public GstreamerCamera() {
        ensureOpen();
    }

    @Override
    public synchronized BufferedImage internalCapture() {
        if (!ensureOpen()) {
            return null;
        }

        // AbstractBroadcastingCamera uses atomic getAndSet on images along with
        // hasNewFrame
        // Should be safe just to return the latest one here.
        newFrame.set(false);
        return currentImage;
    }

    @Override
    public synchronized boolean hasNewFrame() {
        return isOpen() && newFrame.get();
    }

    @Override
    public synchronized void open() throws Exception {
        close();
        clearCalibrationCache();

        if (gstPipeString == null) {
            return;
        }

        Bin bin = null;
        try {
            // Create ghost src pad as we link the whole bin to the appsink
            bin = Gst.parseBinFromDescription(gstPipeString, true);
        } catch (Exception e) {
            Logger.warn("Exception parsing pipeline {}", gstPipeString);
            return;
        }
        if (bin == null) {
            Logger.warn("Failed parsing pipeline {}", gstPipeString);
            return;
        }

        try {
            videosink = new AppSink("GstCamSink");

            videosink.set("emit-signals", true);
            AppSinkListener listener = new AppSinkListener();
            videosink.connect((AppSink.NEW_SAMPLE) listener);
            videosink.connect((AppSink.NEW_PREROLL) listener);
            StringBuilder caps = new StringBuilder("video/x-raw,");
            // JNA creates ByteBuffer using native byte order, set masks according to that.
            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                caps.append("format=BGRA");
            } else {
                caps.append("format=xRGB");
            }
            videosink.setCaps(new Caps(caps.toString()));

            // XXX: What about bus messages? Don't we at least need to drain them?
            pipe = new Pipeline();
            pipe.addMany(bin, videosink);
            Pipeline.linkMany(bin, videosink);
            pipe.play();
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        super.open();
    }

    @Override
    protected synchronized boolean isOpen() {
        return super.isOpen()
                && pipe != null;
    }

    @Override
    public void close() throws IOException {
        super.close();

        if (pipe != null) {
            pipe.stop();
        }
        // Dropping refs here hopefully causes gstreamer to dispose of the objects
        pipe = null;
        videosink = null;
        currentImage = null;
    }

    public String getGstPipeline() {
        return gstPipeString;
    }

    public synchronized void setGstPipeline(String newPipeString) {
        if (this.gstPipeString != null && pipe != null && !this.gstPipeString.equals(newPipeString)) {
            try {
                close();
            } catch (Exception e) {
            }
        }
        gstPipeString = newPipeString;
        Logger.debug("Set pipeline: {}", gstPipeString);
    }

    @Override
    public Wizard getConfigurationWizard() {
        return new GstreamerCameraConfigurationWizard(this);
    }

    @Override
    public String getPropertySheetHolderTitle() {
        return getClass().getSimpleName() + " " + getName();
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return null;
    }

    private BufferedImage getBufferedImage(int width, int height) {
        if (currentImage != null && currentImage.getWidth() == width && currentImage.getHeight() == height) {
            return currentImage;
        }
        if (currentImage != null) {
            currentImage.flush();
        }
        currentImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        currentImage.setAccelerationPriority(0.0f);
        return currentImage;
    }

    // Not sure if we need preroll samples, but the example code showed them anyway.
    private class AppSinkListener implements AppSink.NEW_SAMPLE, AppSink.NEW_PREROLL {

        public void rgbFrame(boolean isPrerollFrame, int width, int height, IntBuffer rgb) {
            try {
                final BufferedImage renderImage = getBufferedImage(width, height);
                int[] pixels = ((DataBufferInt) renderImage.getRaster().getDataBuffer()).getData();
                rgb.get(pixels, 0, width * height);
            } finally {
            }
        }

        private FlowReturn handleSample(Sample sample) {
            Structure capsStruct = sample.getCaps().getStructure(0);
            int w = capsStruct.getInteger("width");
            int h = capsStruct.getInteger("height");
            Buffer buffer = sample.getBuffer();
            ByteBuffer bb = buffer.map(false);
            if (bb != null) {
                rgbFrame(false, w, h, bb.asIntBuffer());
                buffer.unmap();
                newFrame.set(true);
            }
            sample.dispose();
            return FlowReturn.OK;
        }

        @Override
        public FlowReturn newSample(AppSink elem) {
            return handleSample(elem.pullSample());
        }

        @Override
        public FlowReturn newPreroll(AppSink elem) {
            return handleSample(elem.pullPreroll());
        }

    }
}
