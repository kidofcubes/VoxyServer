package com.dripps.voxyserver.client;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class VoxyConfigScreen {

    public static Screen create(Screen parent) {
        int maxRadius = ClientLodSettings.getServerMaxRadius();
        int maxSections = ClientLodSettings.getServerMaxSections();

        if (maxRadius <= 0) maxRadius = 256;
        if (maxSections <= 0) maxSections = 50;

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("VoxyServer Client Settings"))
                .setSavingRunnable(ClientLodSettings::sendPreferences);

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        ConfigCategory category = builder.getOrCreateCategory(Component.literal("LOD Streaming"));

        category.addEntry(entryBuilder.startBooleanToggle(
                        Component.literal("Enable LOD Streaming"),
                        ClientLodSettings.isEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.literal("toggle whether the server sends LOD data to you"))
                .setSaveConsumer(ClientLodSettings::setEnabled)
                .build());

        category.addEntry(entryBuilder.startIntSlider(
                        Component.literal("LOD Stream Radius"),
                        Math.max(0, ClientLodSettings.getPreferredRadius()),
                        0, maxRadius)
                .setDefaultValue(0)
                .setTooltip(Component.literal("how far LODs are streamed in blocks, 0 = server default (" + maxRadius + ")"))
                .setSaveConsumer(ClientLodSettings::setPreferredRadius)
                .build());

        category.addEntry(entryBuilder.startIntSlider(
                        Component.literal("Max Sections Per Tick"),
                        Math.max(0, ClientLodSettings.getPreferredMaxSections()),
                        0, maxSections)
                .setDefaultValue(0)
                .setTooltip(Component.literal("rate limit for sections sent per tick, 0 = server default (" + maxSections + ")"))
                .setSaveConsumer(ClientLodSettings::setPreferredMaxSections)
                .build());

        return builder.build();
    }
}
