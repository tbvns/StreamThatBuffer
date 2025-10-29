package xyz.tbvns.stream_that_bufer.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Stream_that_buferClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("stream_that_bufer");

    @Override
    public void onInitializeClient() {
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            FFmpegStreamingManager.getInstance().captureFrameSafe();
        });

        LOGGER.info("Stream That Buffer initialized successfully");
    }
}