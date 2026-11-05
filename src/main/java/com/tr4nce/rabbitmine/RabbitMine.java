package com.tr4nce.rabbitmine;

import com.tr4nce.rabbitmine.command.RabbitMineCMD;
import com.tr4nce.rabbitmine.config.Config;
import com.tr4nce.rabbitmine.service.HttpService;
import com.tr4nce.rabbitmine.service.RabbitMineConfigService;
import com.tr4nce.rabbitmine.service.RabbitMineRefreshService;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod(RabbitMine.MODID)
public class RabbitMine {
    public static final String MODID = "rabbitmine";

    private HttpService httpService;

    public RabbitMine(ModContainer container) {
        RabbitMineConfigService.init(MODID);
        RabbitMineRefreshService.init();
        importLegacyCommonConfig();
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.addListener(RabbitMineCMD::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private static void importLegacyCommonConfig() {
        Path configDirectory = FMLPaths.CONFIGDIR.get();
        Path legacyConfig = configDirectory.resolve("minesite-common.toml");
        Path newConfig = configDirectory.resolve(MODID + "-common.toml");
        if (Files.exists(newConfig) || !Files.isRegularFile(legacyConfig)) {
            return;
        }
        try {
            Files.copy(legacyConfig, newConfig);
        } catch (IOException e) {
            throw new IllegalStateException("Could not import MineSite common config", e);
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        httpService = new HttpService();
        httpService.startServer();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (httpService != null) {
            httpService.stopServer();
            httpService = null;
        }
    }
}
