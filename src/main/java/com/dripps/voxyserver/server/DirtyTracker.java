package com.dripps.voxyserver.server;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// waits a few ticks before pushing dirty updates bc was causing issues
public class DirtyTracker {
    public static volatile DirtyTracker INSTANCE;

    private static final int PUSH_DELAY_TICKS = 20;
    private static final int MAX_PUSH_ATTEMPTS = 10;

    private final ConcurrentHashMap<DirtySection, Boolean> dirtySections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<DirtySection, PendingPush> pendingPushes = new ConcurrentHashMap<>();
    private final ChunkVoxelizer voxelizer;
    private final LodStreamingService streamingService;
    private final int flushInterval;
    private int tickCounter = 0;
    private long currentTick = 0L;

    private record DirtySection(Identifier dimension, int chunkX, int sectionY, int chunkZ) {}
    private record PendingPush(int nextTick, int attemptsLeft) {}

    public DirtyTracker(ChunkVoxelizer voxelizer, LodStreamingService streamingService, int flushInterval) {
        this.voxelizer = voxelizer;
        this.streamingService = streamingService;
        this.flushInterval = flushInterval;
    }

    public void markDirty(ServerLevel level, int chunkX, int blockY, int chunkZ) {
        Identifier dim = level.dimension().identifier();
        int sectionY = blockY >> 5;
        DirtySection dirtySection = new DirtySection(dim, chunkX, sectionY, chunkZ);
        dirtySections.put(dirtySection, Boolean.TRUE);
        pendingPushes.remove(dirtySection);
    }

    public void tick(MinecraftServer server) {
        currentTick++;
        flushPendingPushes(server);

        if (++tickCounter < flushInterval) return;
        tickCounter = 0;

        if (dirtySections.isEmpty()) return;

        // drain all dirty chunks
        Set<DirtySection> toProcess = ConcurrentHashMap.newKeySet();
        var iter = dirtySections.keySet().iterator();
        while (iter.hasNext()) {
            toProcess.add(iter.next());
            iter.remove();
        }

        record ChunkPosDim(Identifier dimension, int chunkX, int chunkZ) {}
        Set<ChunkPosDim> revoxelized = new java.util.HashSet<>();

        for (DirtySection ds : toProcess) {
            ServerLevel level = findLevel(server, ds.dimension);
            if (level == null) continue;

            // mark world section as pending dirty so streaming doesnt resend stale data
            streamingService.markChunkPendingDirty(ds.chunkX, ds.sectionY, ds.chunkZ);

            ChunkPosDim cpd = new ChunkPosDim(ds.dimension, ds.chunkX, ds.chunkZ);
            if (!revoxelized.contains(cpd)) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(ds.chunkX, ds.chunkZ);
                if (chunk != null) {
                    voxelizer.revoxelizeChunk(level, chunk);
                }
                revoxelized.add(cpd);
            }

            pendingPushes.put(ds, new PendingPush((int) (currentTick + PUSH_DELAY_TICKS), MAX_PUSH_ATTEMPTS));
        }
    }

    private void flushPendingPushes(MinecraftServer server) {
        if (pendingPushes.isEmpty()) return;

        Set<DirtySection> ready = ConcurrentHashMap.newKeySet();
        for (var entry : pendingPushes.entrySet()) {
            if (entry.getValue().nextTick <= currentTick) {
                ready.add(entry.getKey());
            }
        }

        for (DirtySection ds : ready) {
            PendingPush pending = pendingPushes.get(ds);
            if (pending == null || pending.nextTick > currentTick) continue;

            ServerLevel level = findLevel(server, ds.dimension);
            if (level == null) {
                pendingPushes.remove(ds, pending);
                streamingService.clearChunkPendingDirty(ds.chunkX, ds.sectionY, ds.chunkZ);
                continue;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(ds.chunkX, ds.chunkZ);
            if (chunk == null) {
                pendingPushes.remove(ds, pending);
                streamingService.clearChunkPendingDirty(ds.chunkX, ds.sectionY, ds.chunkZ);
                continue;
            }

            streamingService.onSectionDirty(server, level, ds.chunkX, ds.sectionY, ds.chunkZ);

            if (pending.attemptsLeft <= 1) {
                pendingPushes.remove(ds, pending);
                streamingService.clearChunkPendingDirty(ds.chunkX, ds.sectionY, ds.chunkZ);
            } else {
                pendingPushes.replace(ds, pending, new PendingPush((int) (currentTick + PUSH_DELAY_TICKS), pending.attemptsLeft - 1));
            }
        }
    }

    private static ServerLevel findLevel(MinecraftServer server, Identifier dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().equals(dimension)) {
                return level;
            }
        }
        return null;
    }
}
