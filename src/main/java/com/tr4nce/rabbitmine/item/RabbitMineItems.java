package com.tr4nce.rabbitmine.item;

import com.tr4nce.rabbitmine.RabbitMine;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.SimpleTier;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RabbitMineItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RabbitMine.MODID);
    private static final Tier COPPER = new SimpleTier(
            Tiers.STONE.getIncorrectBlocksForDrops(), 190, 5.0F, 1.5F, 12,
            () -> Ingredient.of(Items.COPPER_INGOT)
    );

    static {
        registerHammer("iron", Tiers.IRON);
        registerHammer("copper", COPPER);
        registerHammer("gold", Tiers.GOLD);
        registerHammer("diamond", Tiers.DIAMOND);
    }

    private RabbitMineItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(RabbitMineItems::addCreativeTabItems);
    }

    private static void registerHammer(String material, Tier tier) {
        ITEMS.register(material + "_mining_hammer", () -> new MiningHammerItem(tier,
                new Item.Properties().attributes(MiningHammerItem.createAttributes(tier, 3.0F, -3.2F))));
    }

    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) {
            ITEMS.getEntries().forEach(item -> event.accept(item.get()));
        }
    }
}
