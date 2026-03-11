package com.dripps.voxyserver.server;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// collects dirty chunk positions and flushes them on a timer
public class DirtyTracker {
    public static volatile DirtyTracker INSTANCE;

    private final ConcurrentHashMap<DirtyChunk, Boolean> dirtyChunks = new ConcurrentHashMap<>();
    private final ChunkVoxelizer voxelizer;
    private final LodStreamingService streamingService;
    private final int flushInterval;
    private int tickCounter = 0;

    private record DirtyChunk(Identifier dimension, int chunkX, int chunkZ) {}

    public DirtyTracker(ChunkVoxelizer voxelizer, LodStreamingService streamingService, int flushInterval) {
        this.voxelizer = voxelizer;
        this.streamingService = streamingService;
        this.flushInterval = flushInterval;
    }

    public void markDirty(ServerLevel level, int chunkX, int chunkZ) {
        Identifier dim = level.dimension().identifier();
        dirtyChunks.put(new DirtyChunk(dim, chunkX, chunkZ), Boolean.TRUE);
    }

    public void tick(MinecraftServer server) {
        if (++tickCounter < flushInterval) return;
        tickCounter = 0;

        if (dirtyChunks.isEmpty()) return;

        // drain all dirty chunks
        Set<DirtyChunk> toProcess = ConcurrentHashMap.newKeySet();
        var iter = dirtyChunks.keySet().iterator();
        while (iter.hasNext()) {
            toProcess.add(iter.next());
            iter.remove();
        }

        for (DirtyChunk dc : toProcess) {
            ServerLevel level = null;
            for (ServerLevel l : server.getAllLevels()) {
                if (l.dimension().identifier().equals(dc.dimension)) {
                    level = l;
                    break;
                }
            }
            if (level == null) continue;

            LevelChunk chunk = level.getChunkSource().getChunkNow(dc.chunkX, dc.chunkZ);
            if (chunk == null) continue;

            // revoxelize and push to players in range
            voxelizer.revoxelizeChunk(level, chunk);
            streamingService.onChunkDirty(server, level, dc.chunkX, dc.chunkZ);
        }
    }
}
