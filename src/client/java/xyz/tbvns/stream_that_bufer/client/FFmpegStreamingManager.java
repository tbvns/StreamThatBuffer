package xyz.tbvns.stream_that_bufer.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class FFmpegStreamingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("stream_that_bufer");
    private static FFmpegStreamingManager instance;

    private FFmpegStreamer ffmpegStreamer;
    private boolean isStreaming = false;
    private boolean isInitialized = false;
    private long frameCount = 0;

    private final BlockingQueue<byte[]> frameQueue = new LinkedBlockingQueue<>(10);
    private Thread encoderThread;

    public static synchronized FFmpegStreamingManager getInstance() {
        if (instance == null) {
            instance = new FFmpegStreamingManager();
        }
        return instance;
    }

    private void init(int width, int height) throws IOException {
        if (!isInitialized) {
            ffmpegStreamer = new FFmpegStreamer();
            ffmpegStreamer.startStreaming(width, height, 60);

            // Start encoder thread
            encoderThread = new Thread(this::encoderLoop, "FFmpeg-Encoder");
            encoderThread.setDaemon(true);
            encoderThread.start();

            isStreaming = true;
            isInitialized = true;
            LOGGER.info("FFmpeg streaming started at {}x{}", width, height);
        }
    }

    private void encoderLoop() {
        try {
            while (isStreaming) {
                byte[] frame = frameQueue.take();
                ffmpegStreamer.writeFrame(frame);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOGGER.error("Encoder thread error", e);
            isStreaming = false;
        }
    }

    public void captureFrameSafe() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.getWindow() == null || client.getFramebuffer() == null) {
                return;
            }

            // Lazy initialization on first capture
            if (!isInitialized) {
                int width = client.getWindow().getFramebufferWidth();
                int height = client.getWindow().getFramebufferHeight();
                init(width, height);
            }

            if (!isStreaming || ffmpegStreamer == null) {
                return;
            }

            Framebuffer framebuffer = client.getFramebuffer();
            int width = framebuffer.textureWidth;
            int height = framebuffer.textureHeight;

            ByteBuffer pixelBuffer = BufferUtils.createByteBuffer(width * height * 3);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, framebuffer.getColorAttachment());
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, pixelBuffer);

            //fuck you openGL
            byte[] flipped = flipVertically(pixelBuffer, width, height);

            if (!frameQueue.offer(flipped)) {
                LOGGER.warn("Frame queue full, dropping frame");
            }

            if (++frameCount % 300 == 0) {
                LOGGER.info("Captured {} frames", frameCount);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to capture frame", e);
            isStreaming = false;
        }
    }

    private byte[] flipVertically(ByteBuffer buffer, int width, int height) {
        int rowSize = width * 3;
        byte[] flipped = new byte[rowSize * height];
        byte[] row = new byte[rowSize];

        buffer.rewind();
        for (int y = 0; y < height; y++) {
            buffer.get(row);
            System.arraycopy(row, 0, flipped, (height - 1 - y) * rowSize, rowSize);
        }

        return flipped;
    }

    public void stopStreaming() {
        if (isStreaming && ffmpegStreamer != null) {
            try {
                isStreaming = false;
                if (encoderThread != null) {
                    encoderThread.interrupt();
                    encoderThread.join(1000);
                }
                ffmpegStreamer.stopStreaming();
                LOGGER.info("FFmpeg streaming stopped");
            } catch (Exception e) {
                LOGGER.error("Failed to stop streaming", e);
            }
        }
    }
}