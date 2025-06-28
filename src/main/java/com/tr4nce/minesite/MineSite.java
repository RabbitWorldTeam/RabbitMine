package com.tr4nce.minesite;

import com.mojang.logging.LogUtils;
import com.tr4nce.minesite.command.MineSiteCMD;
import com.tr4nce.minesite.config.Config;
import com.tr4nce.minesite.service.HttpService;
import com.tr4nce.minesite.service.MineSiteConfigService;
import com.tr4nce.minesite.service.MineSiteRefreshService;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(MineSite.MODID)
public class MineSite {

    // Define mod id in a common place for everything to reference
    public static final String MODID = "minesite";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();

    private HttpService httpService;

    public MineSite(FMLJavaModLoadingContext context) {
        MineSiteConfigService.init(MODID);
        MineSiteRefreshService.init();
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(MineSiteCMD.class);
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        IEventBus modBus = context.getModEventBus();
        modBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.addListener(this::onServerStop);
    }
    
    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            httpService = new HttpService();
            httpService.startServer();
        });
    }

    private void onServerStop(FMLCommonSetupEvent event) {
        if (httpService != null) {
            httpService.stopServer();
        }
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            // Some client setup code
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        }
    }
}
