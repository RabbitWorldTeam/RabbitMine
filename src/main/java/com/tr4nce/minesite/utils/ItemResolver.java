package com.tr4nce.minesite.utils;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ItemResolver {
    private static final Logger LOGGER = LogManager.getLogger();

    // 默认物品（当配置无效时使用）
    private static final Item DEFAULT_ITEM = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("minecraft:stick"));

    /**
     * 将配置字符串转换为物品对象
     */
    public static Item getItemFromConfig(String configValue) {
        // 处理可能的空值
        if (configValue == null || configValue.isEmpty()) {
            LOGGER.warn("物品配置为空，使用默认物品");
            return DEFAULT_ITEM;
        }

        // 确保使用小写（资源名称区分大小写）
        configValue = configValue.toLowerCase();

        // 添加默认命名空间（如果未指定）
        if (!configValue.contains(":")) {
            configValue = "minecraft:" + configValue;
        }

        // 尝试解析物品
        ResourceLocation itemId = ResourceLocation.tryParse(configValue);
        if (itemId == null) {
            LOGGER.error("无效的物品ID格式: {}", configValue);
            return DEFAULT_ITEM;
        }

        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null) {
            LOGGER.error("找不到物品: {}", itemId);
            return DEFAULT_ITEM;
        }

        return item;
    }
}
