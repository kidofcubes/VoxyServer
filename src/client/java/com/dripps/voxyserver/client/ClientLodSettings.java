package com.dripps.voxyserver.client;

import com.dripps.voxyserver.network.LODPreferencesPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.Locale;

public class ClientLodSettings {
    private static final ClientLodConfig CONFIG = ClientLodConfig.load();

    private static int serverMaxRadius = -1;
    private static int serverMaxSections = -1;

    private static String activeServerKey;
    private static ClientLodConfig.Preferences activePreferences = CONFIG.getPreferencesForServer(null);

    public static void prepareForCurrentConnection() {
        serverMaxRadius = -1;
        serverMaxSections = -1;
        activeServerKey = resolveCurrentServerKey();
        activePreferences = CONFIG.getPreferencesForServer(activeServerKey);
    }

    public static void applyServerSettings(int maxRadius, int maxSections) {
        serverMaxRadius = maxRadius;
        serverMaxSections = maxSections;
        sendPreferences();
    }

    public static void reset() {
        serverMaxRadius = -1;
        serverMaxSections = -1;
        activeServerKey = null;
        activePreferences = CONFIG.getPreferencesForServer(null);
    }

    public static boolean hasActiveServerProfile() {
        return activeServerKey != null;
    }

    public static boolean isEnabled() {
        return activePreferences.enabled;
    }

    public static int getServerMaxRadius() {
        return serverMaxRadius;
    }

    public static int getServerMaxSections() {
        return serverMaxSections;
    }

    public static int getPreferredRadius() {
        return activePreferences.preferredRadius;
    }

    public static int getPreferredMaxSections() {
        return activePreferences.preferredMaxSections;
    }

    public static void setEnabled(boolean value) {
        activePreferences.enabled = value;
    }

    public static void setPreferredRadius(int radius) {
        activePreferences.preferredRadius = Math.max(0, radius);
    }

    public static void setPreferredMaxSections(int maxSections) {
        activePreferences.preferredMaxSections = Math.max(0, maxSections);
    }

    public static void saveAndSendPreferences() {
        saveActiveProfile();
        sendPreferences();
    }

    public static void sendPreferences() {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }

        ClientPlayNetworking.send(new LODPreferencesPayload(
                activePreferences.enabled,
                activePreferences.preferredRadius,
                activePreferences.preferredMaxSections));
    }

    private static void saveActiveProfile() {
        if (activeServerKey == null) {
            return;
        }

        CONFIG.savePreferencesForServer(activeServerKey, activePreferences);
    }

    private static String resolveCurrentServerKey() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.hasSingleplayerServer() || minecraft.isLocalServer()) {
            return null;
        }

        ServerData currentServer = minecraft.getCurrentServer();
        if (currentServer == null || currentServer.isLan() || currentServer.ip == null || currentServer.ip.isBlank()) {
            return null;
        }

        ServerAddress address = ServerAddress.parseString(currentServer.ip);
        String host = address.getHost();
        if (host == null || host.isBlank()) {
            return null;
        }

        return host.toLowerCase(Locale.ROOT) + ":" + address.getPort();
    }
}
