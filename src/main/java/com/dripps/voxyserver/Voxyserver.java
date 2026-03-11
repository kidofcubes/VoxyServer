package com.dripps.voxyserver;

import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.network.VoxyServerNetworking;
import com.dripps.voxyserver.server.ChunkVoxelizer;
import com.dripps.voxyserver.server.DirtyTracker;
import com.dripps.voxyserver.server.LodStreamingService;
import com.dripps.voxyserver.server.ServerLodEngine;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Voxyserver implements ModInitializer {
    public static final String MOD_ID = "voxyserver";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static VoxyServerConfig config;
    private ServerLodEngine lodEngine;
    private ChunkVoxelizer chunkVoxelizer;
    private LodStreamingService streamingService;
    private DirtyTracker dirtyTracker;

    public static VoxyServerConfig getConfig() {
        return config;
    }

    @Override
    public void onInitialize() {
        config = VoxyServerConfig.load();
        LOGGER.info("VoxyServer initialized");
        VoxyServerNetworking.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var worldPath = server.getWorldPath(LevelResource.ROOT);
            lodEngine = new ServerLodEngine(worldPath);
            chunkVoxelizer = new ChunkVoxelizer(lodEngine, config);
            chunkVoxelizer.register();
            streamingService = new LodStreamingService(lodEngine, config);
            streamingService.register();
            if (config.dirtyTrackingEnabled) {
                dirtyTracker = new DirtyTracker(chunkVoxelizer, streamingService, config.dirtyTrackingInterval);
                DirtyTracker.INSTANCE = dirtyTracker;
                ServerTickEvents.END_SERVER_TICK.register(dirtyTracker::tick);
            }
            LOGGER.info("VoxyServer engine started for world: {}", worldPath);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (lodEngine != null) {
                LOGGER.info("shutting down VoxyServer engine");
                DirtyTracker.INSTANCE = null;
                dirtyTracker = null;
                if (streamingService != null) streamingService.shutdown();
                lodEngine.shutdown();
                lodEngine = null;
                chunkVoxelizer = null;
                streamingService = null;
            }
        });

        // thius handles dimension changes, clear players lod cache for old dimension
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            if (streamingService != null) {
                streamingService.onDimensionChange(player, destination);
            }
        });
    }
}
