package com.tr4nce.minesite.service;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import com.tr4nce.minesite.utils.MineSiteUtils;
import net.minecraft.core.BlockPos;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class MineSiteConfigService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Path configPath;
    private static JsonObject cachedConfig;

    public static void init(String modId) {
        configPath = FMLPaths.CONFIGDIR.get().resolve(modId + ".json");
        createConfigIfMissing();
        loadConfig();
    }

    private static void createConfigIfMissing() {
        try {
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath.getParent());
                Files.createFile(configPath);

                // 创建默认配置结构
                JsonObject root = new JsonObject();
                root.addProperty("version", "1.1");
                root.add("sites", new JsonArray());

                Files.writeString(configPath, GSON.toJson(root));
            }
        } catch (IOException e) {
            throw new RuntimeException("无法创建配置文件", e);
        }
    }

    public static boolean addNewSite(String name, String creator, BlockPos pos1, BlockPos pos2, String dimension) {
        try {
            // 读取现有配置
            JsonObject config = GSON.fromJson(Files.readString(configPath), JsonObject.class);
            JsonArray sites = config.getAsJsonArray("sites");

            // 创建新站点
            JsonObject newSite = createSiteTemplate(name, creator, pos1, pos2, dimension);

            // 添加新站点
            sites.add(newSite);

            // 写回文件
            Files.writeString(configPath, GSON.toJson(config));
        } catch (IOException e) {
            throw new RuntimeException("添加新站点失败", e);
        }
        return true;
    }

    private static JsonObject createSiteTemplate(String name, String creator, BlockPos pos1, BlockPos pos2, String dimension) {
        JsonObject site = new JsonObject();
        site.addProperty("name", name);
        site.addProperty("creator", creator);
        site.addProperty("description", "新创建的站点");
        site.addProperty("world", dimension); // 默认世界
        site.addProperty("pos1", formatBlockPos(pos1));
        site.addProperty("pos2", formatBlockPos(pos2));
        site.addProperty("safetyPoint", "");
        site.addProperty("status", "inactive"); // 初始状态为未激活
        site.addProperty("broadcastInterval", 300); // 默认广播间隔为5分钟
        site.addProperty("refreshInterval", 60);

        // 默认矿场开启时间
        JsonArray timeTable = new JsonArray();
        List<Integer> weekdays = List.of(0, 1, 2, 3, 4, 5, 6); // 周日到周六
        for (int weekday : weekdays) {
            JsonObject timeEntry = new JsonObject();
            timeEntry.addProperty("weekday", weekday);
            timeEntry.addProperty("startTime", "00:00");
            timeEntry.addProperty("endTime", "23:59");
            timeEntry.addProperty("fullDayClose", false); // 默认不全天关闭
            timeTable.add(timeEntry);
        }
        site.add("timeTable", timeTable);

        // 默认矿石配置
        JsonArray mines = new JsonArray();
        JsonObject defaultMine = new JsonObject();
        defaultMine.addProperty("block", "minecraft:stone");
        defaultMine.addProperty("weight", 1);
        mines.add(defaultMine);
        site.add("mines", mines);

        // 时间戳
        String now = formatCurrentTime();
        site.addProperty("createTime", now);
        site.addProperty("lastUpdateTime", now);
        site.addProperty("lastRefreshTime", now); // 初始刷新时间为创建时间

        return site;
    }

    public static boolean deleteSite(String name) {
        try {
            // 读取现有配置
            String content = Files.readString(configPath);
            JsonObject config = GSON.fromJson(content, JsonObject.class);
            JsonArray sites = config.getAsJsonArray("sites");
            // 查找并删除指定站点
            boolean found = false;
            for (int i = 0; i < sites.size(); i++) {
                JsonObject site = sites.get(i).getAsJsonObject();
                if (site.get("name").getAsString().equals(name)) {
                    sites.remove(i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                LOGGER.warn("尝试删除不存在的矿场: {}", name);
                return false;
            }
            // 写回文件
            Files.writeString(configPath, GSON.toJson(config));
            LOGGER.info("成功删除矿场: {}", name);
            return true;
        } catch (IOException e) {
            LOGGER.error("删除矿场失败", e);
            throw new RuntimeException("删除站点失败: " + e.getMessage(), e);
        }
    }

    private static String formatBlockPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String formatCurrentTime() {
        Instant instant = Instant.now();
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    // 强制重载配置
    public static void forceReloadConfig() {
        // 重新加载配置
        loadConfig();
        LOGGER.info("强制重载配置文件成功");
    }

    // 获取完整配置对象
    public static JsonObject getFullConfig() throws IOException {
        return cachedConfig;
    }

    public static boolean enableSite(String siteName) {
        try {
            JsonObject config = getFullConfig();
            JsonArray sites = config.getAsJsonArray("sites");

            for (JsonElement element : sites) {
                JsonObject site = element.getAsJsonObject();
                if (siteName.equals(site.get("name").getAsString())) {
                    site.addProperty("status", "active");
                    site.addProperty("lastUpdateTime", formatCurrentTime());
                    saveConfig(config);
                    LOGGER.info("矿场 {} 已启用", siteName);
                    return true;
                }
            }
            LOGGER.warn("启用矿场时未找到矿场: {}", siteName);
        } catch (IOException e) {
            LOGGER.error("启用矿场失败", e);
        }
        return false;
    }
    
    public static boolean setSafetyPoint(String siteName, String safetyPointString) {
        try {
            BlockPos safetyPoint = MineSiteUtils.parseBlockPos(safetyPointString);
            JsonObject config = getFullConfig();
            JsonArray sites = config.getAsJsonArray("sites");

            for (JsonElement element : sites) {
                JsonObject site = element.getAsJsonObject();
                if (siteName.equals(site.get("name").getAsString())) {
                    site.addProperty("safetyPoint", formatBlockPos(safetyPoint));
                    site.addProperty("lastUpdateTime", formatCurrentTime());
                    saveConfig(config);
                    LOGGER.info("矿场 {} 的安全点已设置为 {}", siteName, formatBlockPos(safetyPoint));
                    return true;
                }
            }
            LOGGER.warn("设置安全点时未找到矿场: {}", siteName);
        } catch (IOException e) {
            LOGGER.error("设置安全点失败", e);
        }
        return false;
    }

    public static boolean disableSite(String siteName) {
        try {
            JsonObject config = getFullConfig();
            JsonArray sites = config.getAsJsonArray("sites");

            for (JsonElement element : sites) {
                JsonObject site = element.getAsJsonObject();
                if (siteName.equals(site.get("name").getAsString())) {
                    site.addProperty("status", "inactive");
                    site.addProperty("lastUpdateTime", formatCurrentTime());
                    saveConfig(config);
                    LOGGER.info("矿场 {} 已禁用", siteName);
                    return true;
                }
            }
            LOGGER.warn("关闭矿场时未找到矿场: {}", siteName);
        } catch (IOException e) {
            LOGGER.error("禁用矿场失败", e);
        }
        return false;
    }

    // 保存配置
    public static void saveConfig(JsonObject config) throws IOException {
        Files.writeString(configPath, GSON.toJson(config));
    }

    public static void loadConfig() {
        try {
            cachedConfig = GSON.fromJson(Files.readString(configPath), JsonObject.class);
            LOGGER.info("配置文件加载成功");
        } catch (IOException e) {
            throw new RuntimeException("加载配置文件失败", e);
        }
    }
}
