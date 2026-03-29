package com.dripps.voxyserver.client;

import com.dripps.voxyserver.network.LODPreferencesPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class ClientLodSettings {
    private static int serverMaxRadius = -1;
    private static int serverMaxSections = -1;

    private static boolean enabled = true;
    private static int preferredRadius = -1;
    private static int preferredMaxSections = -1;

    public static void applyServerSettings(int maxRadius, int maxSections) {
        serverMaxRadius = maxRadius;
        serverMaxSections = maxSections;
    }

    public static void reset() {
        serverMaxRadius = -1;
        serverMaxSections = -1;
        enabled = true;
        preferredRadius = -1;
        preferredMaxSections = -1;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int getServerMaxRadius() {
        return serverMaxRadius;
    }

    public static int getServerMaxSections() {
        return serverMaxSections;
    }

    public static int getPreferredRadius() {
        return preferredRadius;
    }

    public static int getPreferredMaxSections() {
        return preferredMaxSections;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void setPreferredRadius(int radius) {
        preferredRadius = radius;
    }

    public static void setPreferredMaxSections(int maxSections) {
        preferredMaxSections = maxSections;
    }

    public static void sendPreferences() {
        ClientPlayNetworking.send(new LODPreferencesPayload(enabled, preferredRadius, preferredMaxSections));
    }
}
