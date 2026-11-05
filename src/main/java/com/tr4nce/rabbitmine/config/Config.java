package com.tr4nce.rabbitmine.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> WELCOME_MESSAGE;
    public static final ModConfigSpec.ConfigValue<String> SELECTION_TOOL;
    public static final ModConfigSpec.ConfigValue<Integer> SERVER_PORT;
    public static final ModConfigSpec.ConfigValue<Integer> SITE_REFRESH_SPEED;

    static {
        BUILDER.push("General Settings");

        WELCOME_MESSAGE = BUILDER
                .comment("Welcome message to display to players when they join the server.")
                .define("welcomeMessage", "Welcome to the RabbitMine server!");

        SELECTION_TOOL = BUILDER
                .comment("Item used for selecting regions. Default is a wooden_hoe.")
                .define("selectionTool", "minecraft:wooden_hoe");

        SERVER_PORT = BUILDER
                .comment("Port for the HTTP server to listen on.")
                .defineInRange("serverPort", 8124, 1024, 65535);

        SITE_REFRESH_SPEED = BUILDER
                .comment("Speed at which the site refreshes in seconds. Default is 60 seconds.")
                .defineInRange("siteRefreshSpeed", 100, 1, Integer.MAX_VALUE);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
