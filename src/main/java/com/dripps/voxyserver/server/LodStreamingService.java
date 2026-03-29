package com.dripps.voxyserver.server;

import com.dripps.voxyserver.Voxyserver;
import com.dripps.voxyserver.network.LODBulkPayload;
import com.dripps.voxyserver.network.LODClearPayload;
import com.dripps.voxyserver.network.LODPreferencesPayload;
import com.dripps.voxyserver.network.LODReadyPayload;
import com.dripps.voxyserver.network.LODSectionPayload;
import com.dripps.voxyserver.network.LODServerSettingsPayload;
import com.dripps.voxyserver.util.IdRemapper;
import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Set;

public class LodStreamingService {
    private final ServerLodEngine engine;
    private final int lodStreamRadius;
    private final int maxSectionsPerTick;
    private final int sectionsPerPacket;
    private final int tickInterval;
    private final ConcurrentHashMap<UUID, PlayerLodTracker> trackers = new ConcurrentHashMap<>();
    private final Set<Long> pendingDirtyKeys = ConcurrentHashMap.newKeySet();
    private final ExecutorService streamExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoxyServer Streaming");
        t.setDaemon(true);
        return t;
    });
    private int tickCounter = 0;

    public LodStreamingService(ServerLodEngine engine, com.dripps.voxyserver.config.VoxyServerConfig config) {
        this.engine = engine;
        this.lodStreamRadius = config.lodStreamRadius;
        this.maxSectionsPerTick = config.maxSectionsPerTickPerPlayer;
        this.sectionsPerPacket = config.sectionsPerPacket;
        this.tickInterval = config.tickInterval;
    }

    public void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var tracker = new PlayerLodTracker();
            trackers.put(handler.getPlayer().getUUID(), tracker);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            trackers.remove(handler.getPlayer().getUUID());
        });

        ServerPlayNetworking.registerGlobalReceiver(LODReadyPayload.TYPE, (payload, context) -> {
            var tracker = trackers.get(context.player().getUUID());
            if (tracker != null) {
                tracker.setReady(true);
                Voxyserver.LOGGER.info("player {} is ready for LOD streaming", context.player().getName().getString());
                ServerPlayNetworking.send(context.player(),
                        new LODServerSettingsPayload(lodStreamRadius, maxSectionsPerTick));
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(LODPreferencesPayload.TYPE, (payload, context) -> {
            var tracker = trackers.get(context.player().getUUID());
            if (tracker == null) return;
            tracker.setLodEnabled(payload.enabled());
            tracker.setPreferredRadius(payload.lodStreamRadius());
            tracker.setPreferredMaxSections(payload.maxSectionsPerTick());
            if (!payload.enabled()) {
                tracker.reset();
            }
            Voxyserver.LOGGER.info("player {} updated LOD preferences: enabled={}, radius={}, maxSections={}",
                    context.player().getName().getString(), payload.enabled(),
                    payload.lodStreamRadius(), payload.maxSectionsPerTick());
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        // when a chunk is (re)voxelized, invalidate the sent flag so the updated section gets restreamed
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> invalidateForChunk(level, chunk.getPos().x, chunk.getPos().z));
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> invalidateForChunk(level, chunk.getPos().x, chunk.getPos().z));
    }

    public void markChunkPendingDirty(int chunkX, int sectionY, int chunkZ) {
        long key = WorldEngine.getWorldSectionId(0, chunkX >> 1, sectionY, chunkZ >> 1);
        pendingDirtyKeys.add(key);
    }

    public void clearChunkPendingDirty(int chunkX, int sectionY, int chunkZ) {
        long key = WorldEngine.getWorldSectionId(0, chunkX >> 1, sectionY, chunkZ >> 1);
        pendingDirtyKeys.remove(key);
    }

    private void invalidateForChunk(ServerLevel level, int chunkX, int chunkZ) {
        int worldSecX = chunkX >> 1;
        int worldSecZ = chunkZ >> 1;
        int minY = level.getMinSectionY() >> 1;
        int maxY = (level.getMaxSectionY() >> 1) + 1;
        for (int secY = minY; secY < maxY; secY++) {
            long key = WorldEngine.getWorldSectionId(0, worldSecX, secY, worldSecZ);
            for (var tracker : trackers.values()) {
                tracker.invalidate(key);
            }
        }
    }

    public void shutdown() {
        streamExecutor.shutdownNow();
        try {
            streamExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // snapshot player state on tick thread, do heavy shtuff async
    private record PlayerSnapshot(UUID uuid, int chunkX, int chunkZ,
                                   WorldIdentifier worldId, Identifier dimension,
                                   int minY, int maxY,
                                   Registry<Biome> biomeRegistry) {}

    private void onServerTick(MinecraftServer server) {
        if (++tickCounter < tickInterval) return;
        tickCounter = 0;

        List<PlayerSnapshot> snapshots = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var tracker = trackers.get(player.getUUID());
            if (tracker == null || !tracker.isReady() || !tracker.isLodEnabled()) continue;

            tracker.updatePosition(player);
            ServerLevel level = player.level();
            WorldIdentifier worldId = WorldIdentifier.of(level);
            if (worldId == null) continue;

            snapshots.add(new PlayerSnapshot(
                    player.getUUID(),
                    tracker.getLastChunkX(),
                    tracker.getLastChunkZ(),
                    worldId,
                    level.dimension().identifier(),
                    level.getMinSectionY() >> 1,
                    (level.getMaxSectionY() >> 1) + 1,
                    level.registryAccess().lookupOrThrow(Registries.BIOME)
            ));
        }

        if (!snapshots.isEmpty()) {
            streamExecutor.execute(() -> processSnapshots(server, snapshots));
        }
    }

    private void processSnapshots(MinecraftServer server, List<PlayerSnapshot> snapshots) {
        for (PlayerSnapshot snap : snapshots) {
            var tracker = trackers.get(snap.uuid);
            if (tracker == null || !tracker.isReady()) continue;

            try {
                if (com.dripps.voxyserver.util.ServerStatsTracker.INSTANCE != null) {
                    com.dripps.voxyserver.util.ServerStatsTracker.INSTANCE.markStreamed();
                }
                streamForSnapshot(server, snap, tracker);
            } catch (Exception e) {
                Voxyserver.LOGGER.error("error streaming LODs for player {}", snap.uuid, e);
            }
        }
    }

    // push updated sections for a dirty chunk to all players who already have it
    public void onSectionDirty(MinecraftServer server, ServerLevel level, int chunkX, int sectionY, int chunkZ) {
        int worldSecX = chunkX >> 1;
        int worldSecZ = chunkZ >> 1;

        WorldIdentifier worldId = WorldIdentifier.of(level);
        if (worldId == null) return;

        Identifier dimension = level.dimension().identifier();
        Registry<Biome> biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);

        streamExecutor.execute(() -> {
            WorldEngine world = engine.getOrCreate(worldId);
            if (world == null) return;
            Mapper mapper = world.getMapper();

            long key = WorldEngine.getWorldSectionId(0, worldSecX, sectionY, worldSecZ);
            WorldSection section = world.acquireIfExists(0, worldSecX, sectionY, worldSecZ);
            if (section == null) return;

            LODSectionPayload payload;
            try {
                payload = serializeSection(section, dimension, mapper, biomeRegistry);
            } finally {
                section.release();
            }
            if (payload == null) return;

            LODSectionPayload finalPayload = payload;
            long sectionKey = key;
            // send to all ready players within lod range (not gated on hasSent,
            // because invalidateForChunk may have cleared it)
            for (var entry : trackers.entrySet()) {
                PlayerLodTracker tracker = entry.getValue();
                if (!tracker.isReady()) continue;

                // only send to players whose LOD radius covers this section
                int playerWorldSecX = tracker.getLastChunkX() >> 1;
                int playerWorldSecZ = tracker.getLastChunkZ() >> 1;
                int effectiveRadius = tracker.getEffectiveRadius(lodStreamRadius);
                int radiusSections = effectiveRadius >> 1;
                if (Math.abs(worldSecX - playerWorldSecX) > radiusSections
                        || Math.abs(worldSecZ - playerWorldSecZ) > radiusSections) continue;

                tracker.markSent(sectionKey); // make sure dont send stale data

                UUID playerId = entry.getKey();
                if (com.dripps.voxyserver.util.ServerStatsTracker.INSTANCE != null) {
                    com.dripps.voxyserver.util.ServerStatsTracker.INSTANCE.markStreamed();
                }
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player != null && player.level() == level) {
                        ServerPlayNetworking.send(player, finalPayload);
                    }
                });
            }
        });
    }

    public void onDimensionChange(ServerPlayer player, ServerLevel newLevel) {
        var tracker = trackers.get(player.getUUID());
        if (tracker == null || !tracker.isReady()) return;

        tracker.reset();
        Identifier dim = newLevel.dimension().identifier();
        ServerPlayNetworking.send(player, LODClearPayload.clearDimension(dim));
    }

    public void clearDimensionForReadyPlayers(ServerLevel level) {
        Identifier dim = level.dimension().identifier();
        for (var entry : trackers.entrySet()) {
            PlayerLodTracker tracker = entry.getValue();
            if (tracker == null || !tracker.isReady()) continue;

            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.level() != level) continue;

            tracker.reset();
            ServerPlayNetworking.send(player, LODClearPayload.clearDimension(dim));
        }
    }

    private void streamForSnapshot(MinecraftServer server, PlayerSnapshot snap, PlayerLodTracker tracker) {
        WorldEngine world = engine.getOrCreate(snap.worldId);
        if (world == null) return;

        int playerWorldSecX = snap.chunkX >> 1;
        int playerWorldSecZ = snap.chunkZ >> 1;

        int effectiveRadius = tracker.getEffectiveRadius(lodStreamRadius);
        int effectiveMaxSections = tracker.getEffectiveMaxSections(maxSectionsPerTick);
        int radiusSections = effectiveRadius >> 1;
        Mapper mapper = world.getMapper();

        List<LODSectionPayload> batch = new ArrayList<>();
        int sent = 0;

        for (int dist = 0; dist <= radiusSections && sent < effectiveMaxSections; dist++) {
            for (int dx = -dist; dx <= dist && sent < effectiveMaxSections; dx++) {
                for (int dz = -dist; dz <= dist && sent < effectiveMaxSections; dz++) {
                    if (Math.abs(dx) != dist && Math.abs(dz) != dist) continue;

                    int secX = playerWorldSecX + dx;
                    int secZ = playerWorldSecZ + dz;

                    for (int secY = snap.minY; secY < snap.maxY && sent < effectiveMaxSections; secY++) {
                        long key = WorldEngine.getWorldSectionId(0, secX, secY, secZ);
                        if (tracker.hasSent(key)) continue;
                        if (pendingDirtyKeys.contains(key)) continue;

                        WorldSection section = world.acquireIfExists(0, secX, secY, secZ);
                        if (section == null) continue;

                        try {
                            LODSectionPayload payload = serializeSection(section, snap.dimension, mapper, snap.biomeRegistry);
                            if (payload != null) {
                                batch.add(payload);
                                sent++;
                            }
                            tracker.markSent(key);
                        } finally {
                            section.release();
                        }
                    }
                }
            }
        }

        if (!batch.isEmpty()) {
            List<LODSectionPayload> toSend = List.copyOf(batch);
            Identifier dim = snap.dimension;
            UUID playerId = snap.uuid;
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) return;
                if (!player.level().dimension().identifier().equals(dim)) return;
                for (int i = 0; i < toSend.size(); i += sectionsPerPacket) {
                    List<LODSectionPayload> chunk = toSend.subList(i, Math.min(toSend.size(), i + sectionsPerPacket));
                    if (chunk.size() == 1) {
                        ServerPlayNetworking.send(player, chunk.getFirst());
                    } else {
                        ServerPlayNetworking.send(player, new LODBulkPayload(dim, chunk));
                    }
                }
            });
        }
    }

    private LODSectionPayload serializeSection(WorldSection section, Identifier dimension,
                                                Mapper mapper, Registry<Biome> biomeRegistry) {
        long[] data = section.copyData();

        // build LUT of unique mapping ids
        Long2ShortOpenHashMap lutMap = new Long2ShortOpenHashMap();
        lutMap.defaultReturnValue((short) -1);
        short lutIndex = 0;

        short[] indexArray = new short[data.length];
        for (int i = 0; i < data.length; i++) {
            long id = data[i];
            short idx = lutMap.putIfAbsent(id, lutIndex);
            if (idx == -1) {
                idx = lutIndex++;
            }
            indexArray[i] = idx;
        }

        // convert LUT from voxy mapper ids to vanilla registry ids
        int[] lutBlockStateIds = new int[lutIndex];
        int[] lutBiomeIds = new int[lutIndex];
        byte[] lutLight = new byte[lutIndex];

        for (var entry : lutMap.long2ShortEntrySet()) {
            long mappingId = entry.getLongKey();
            short idx = entry.getShortValue();
            lutBlockStateIds[idx] = IdRemapper.toVanillaBlockStateId(mapper, mappingId);
            lutBiomeIds[idx] = IdRemapper.toVanillaBiomeIdFromMapper(mapper, mappingId, biomeRegistry);
            lutLight[idx] = (byte) IdRemapper.getLightFromMapping(mappingId);
        }

        return new LODSectionPayload(dimension, section.key, lutBlockStateIds, lutBiomeIds, lutLight, indexArray);
    }
}
