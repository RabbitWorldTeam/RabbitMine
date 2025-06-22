package com.tr4nce.minesite.utils;

import com.tr4nce.minesite.MineSite;
import com.tr4nce.minesite.config.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;


@Mod.EventBusSubscriber(modid = MineSite.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RegionSelector {
    private static final Map<String, BlockPos> leftClickPositions = new HashMap<>();
    private static final Map<String, BlockPos> rightClickPositions = new HashMap<>();
    private static final Map<String, ResourceKey<Level>> playerDimensions = new HashMap<>();

    @SubscribeEvent
    public static void onBlockLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        handleSelectionEvent(event.getEntity(), event.getPos(), event.getLevel(), true);
        if (isUsingSelectionTool(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBlockRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() == InteractionHand.MAIN_HAND) {
            handleSelectionEvent(event.getEntity(), event.getPos(), event.getLevel(), false);
            if (isUsingSelectionTool(event.getEntity())) {
                event.setCanceled(true);
            }
        }
    }

    private static boolean isUsingSelectionTool(Player player) {
        Item selectionTool = ItemResolver.getItemFromConfig(Config.SELECTION_TOOL.get());
        ItemStack heldItem = player.getMainHandItem();
        return heldItem.getItem() == selectionTool;
    }

    /**
     * 获取玩家选择的两个位置（包含维度信息）
     */
    public static SelectionData getSelectionData(Player player) {
        String key = getStorageKey(player);
        BlockPos pos1 = leftClickPositions.get(key);
        BlockPos pos2 = rightClickPositions.get(key);
        ResourceKey<Level> dimension = playerDimensions.get(key);

        if (pos1 != null && pos2 != null && dimension != null) {
            return new SelectionData(pos1, pos2, dimension);
        }
        return null;
    }

    private static void handleSelectionEvent(Player player, BlockPos pos, Level level, boolean isLeftClick) {
        if (level.isClientSide) return;

        // 检查是否是选择工具
        Item selectionTool = ItemResolver.getItemFromConfig(Config.SELECTION_TOOL.get());
        ItemStack heldItem = player.getMainHandItem();
        if (heldItem.getItem() != selectionTool) return;

        String key = getStorageKey(player);
        ResourceKey<Level> dimension = level.dimension();

        // 存储维度信息
        playerDimensions.put(key, dimension);

        if (isLeftClick) {
            leftClickPositions.put(key, pos);
            player.sendSystemMessage(
                    Component.literal("§a左键位置已设置: §e(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")")
            );
        } else {
            rightClickPositions.put(key, pos);
            player.sendSystemMessage(
                    Component.literal("§a右键位置已设置: §e(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")")
            );
        }

        // 检查是否完成两个位置的选择
        checkBothPositions(player, key);
    }

    private static void checkBothPositions(Player player, String key) {
        if (leftClickPositions.containsKey(key) && rightClickPositions.containsKey(key)) {
            BlockPos pos1 = leftClickPositions.get(key);
            BlockPos pos2 = rightClickPositions.get(key);

            player.sendSystemMessage(Component.literal("§b§l区域选择完成:"));
            player.sendSystemMessage(Component.literal("§a左键位置: §e" + formatPos(pos1)));
            player.sendSystemMessage(Component.literal("§a右键位置: §e" + formatPos(pos2)));

            // calculate and display the area
            int minX = Math.min(pos1.getX(), pos2.getX());
            int minY = Math.min(pos1.getY(), pos2.getY());
            int minZ = Math.min(pos1.getZ(), pos2.getZ());
            int maxX = Math.max(pos1.getX(), pos2.getX());
            int maxY = Math.max(pos1.getY(), pos2.getY());
            int maxZ = Math.max(pos1.getZ(), pos2.getZ());

            int width = maxX - minX + 1;
            int height = maxY - minY + 1;
            int depth = maxZ - minZ + 1;
            int volume = width * height * depth;

            player.sendSystemMessage(
                    Component.literal("§a区域大小: §e" + width + " x " + height + " x " + depth + " (" + volume + "个方块)")
            );
            player.sendSystemMessage(Component.literal("§a现在可以使用 §e/minesite create <名称> §a创建矿场!"));
        } else {
            player.sendSystemMessage(Component.literal("§c请先完成左键和右键的选择。"));
        }
    }

    private static String formatPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static String getStorageKey(Player player) {
        return player.getUUID().toString();
    }

    public static class SelectionData {
        public final BlockPos pos1;
        public final BlockPos pos2;
        public final ResourceKey<Level> dimension;

        public SelectionData(BlockPos pos1, BlockPos pos2, ResourceKey<Level> dimension) {
            this.pos1 = pos1;
            this.pos2 = pos2;
            this.dimension = dimension;
        }

        public String getDimensionName() {
            ResourceLocation loc = dimension.location();
            return loc.getNamespace() + ":" + loc.getPath();
        }
    }
}
