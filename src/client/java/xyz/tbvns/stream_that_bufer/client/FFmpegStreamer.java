package xyz.tbvns.stream_that_bufer.client;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;


public class FFmpegStreamer {
    private static final Logger LOGGER = LoggerFactory.getLogger("stream_that_bufer");
    private static final int HTTP_PORT = 8000;
    private static final String OUTPUT_DIR = "output";

    private Process ffmpegProcess;
    private OutputStream ffmpegStdin;
    private HttpServer httpServer;

    public void startStreaming(int width, int height, int fps) throws IOException {
        new File(OUTPUT_DIR).mkdirs();
        startHttpServer();

        String[] cmd = {
                "ffmpeg",
                "-f", "rawvideo",
                "-pix_fmt", "rgb24",
                "-s", width + "x" + height,
                "-r", String.valueOf(fps),
                "-i", "-",
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-tune", "zerolatency",
                "-g", String.valueOf(fps),
                "-b:v", "2500k",
                "-maxrate", "2500k",
                "-bufsize", "5000k",
                "-f", "hls",
                "-hls_time", "2",
                "-hls_list_size", "8",
                "-hls_flags", "delete_segments",
                "-hls_segment_type", "mpegts",
                "-hls_allow_cache", "0",
                "-start_number", "0",
                OUTPUT_DIR + "/stream.m3u8"
        };

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        ffmpegProcess = pb.start();
        ffmpegStdin = ffmpegProcess.getOutputStream();

        new Thread(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(ffmpegProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.info("[FFmpeg] {}", line);
                }
            } catch (IOException e) {
                LOGGER.error("Error reading FFmpeg output", e);
            }
        }).start();

        LOGGER.info("FFmpeg process started with {}x{} @ {} fps", width, height, fps);
        LOGGER.info("Stream available at http://localhost:8000/stream.m3u8");
    }

    private void startHttpServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", HTTP_PORT), 0);
        httpServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/stream.m3u8";
            }

            File file = new File(OUTPUT_DIR + path);

            if (file.exists() && file.isFile()) {
                exchange.getResponseHeaders().set("Content-Type", getContentType(path));
                exchange.sendResponseHeaders(200, file.length());

                try (FileInputStream fis = new FileInputStream(file);
                     OutputStream os = exchange.getResponseBody()) {
                    fis.transferTo(os);
                }
            } else {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
            }
        });
        httpServer.setExecutor(null);
        httpServer.start();
        LOGGER.info("HTTP server started on port {}", HTTP_PORT);
    }

    private String getContentType(String path) {
        if (path.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (path.endsWith(".ts")) return "video/mp2t";
        return "application/octet-stream";
    }

    int frameCount = 0;

    public void writeFrame(byte[] frameData) throws IOException {
        ffmpegStdin.write(frameData);
        if (frameCount++ % 10 == 0) {
            ffmpegStdin.flush();
        }
    }
    public void stopStreaming() throws IOException {
        if (ffmpegStdin != null) {
            ffmpegStdin.close();
        }
        if (ffmpegProcess != null) {
            try {
                ffmpegProcess.waitFor();
            } catch (InterruptedException e) {
                ffmpegProcess.destroy();
                Thread.currentThread().interrupt();
            }
        }
        if (httpServer != null) {
            httpServer.stop(0);
            LOGGER.info("HTTP server stopped");
        }
        LOGGER.info("FFmpeg process terminated");
    }
}