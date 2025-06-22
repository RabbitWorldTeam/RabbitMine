package com.tr4nce.minesite.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.tr4nce.minesite.MineSite;
import com.tr4nce.minesite.notification.SchedulerManager;
import com.tr4nce.minesite.utils.MineSiteUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


@Mod.EventBusSubscriber(modid = MineSite.MODID)
public class MineSiteRefreshService {
    // 矿场刷新任务队列
    private static final Map<String, Queue<BlockPos>> siteRefreshQueues = new ConcurrentHashMap<>();
    // 矿场方块状态映射
    private static final Map<String, Map<BlockPos, BlockState>> siteBlockStates = new ConcurrentHashMap<>();
    // 矿场配置缓存
    private static final Map<String, JsonObject> siteConfigs = new ConcurrentHashMap<>();
    // 矿场维度缓存
    private static final Map<String, ResourceKey<Level>> siteDimensions = new ConcurrentHashMap<>();
    // 跟踪已经清除的矿场区域
    private static final Set<String> clearedSites = ConcurrentHashMap.newKeySet();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DEFAULT_TIME_ZONE = TimeZone.getDefault().getID();
    private static final Object reloadLock = new Object();
    private static final Map<String, RefreshMetrics> refreshMetrics = new ConcurrentHashMap<>();

    // 刷新性能指标类
    private static class RefreshMetrics {
        long startTime;          // 刷新开始时间
        long prepareTime;        // 准备阶段耗时
        int totalBlocks;         // 总方块数量
        int blocksProcessed;     // 已处理方块数量
        int ticksTaken;          // 消耗的tick数量
    }
    
    // 初始化矿场刷新服务
    public static void init() {
        synchronized (reloadLock) {
            loadConfigs();
            scheduleInitialRefreshes();
        }
    }

    // 从配置文件加载所有矿场
    private static void loadConfigs() {
        siteConfigs.clear();
        siteDimensions.clear();

        try {
            JsonObject config = MineSiteConfigService.getFullConfig();
            JsonArray sites = config.getAsJsonArray("sites");

            for (JsonElement element : sites) {
                JsonObject site = element.getAsJsonObject();
                String siteName = site.get("name").getAsString();
                siteConfigs.put(siteName, site);
                LOGGER.info("Loaded Site Config: {}", siteName);

                // 验证安全点配置
                if (!site.has("safetyPoint")) {
                    LOGGER.warn("矿场 {} 缺少安全点(safetyPoint)配置！", siteName);
                } else {
                    try {
                        MineSiteUtils.parseBlockPos(site.get("safetyPoint").getAsString());
                    } catch (Exception e) {
                        LOGGER.error("矿场 {} 的安全点配置无效: {}", siteName, e.getMessage());
                    }
                }

                // 解析维度
                String dimStr = site.get("world").getAsString();
                ResourceLocation dimLoc = ResourceLocation.tryParse(dimStr);
                if (dimLoc != null) {
                    siteDimensions.put(siteName, ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION,
                            dimLoc
                    ));
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load sites from config", e);
        }
    }

    public static void reloadSites() {
        synchronized (reloadLock) {
            LOGGER.info("开始重新加载矿场配置...");

            // 清除所有缓存
            siteRefreshQueues.clear();
            siteBlockStates.clear();
            siteConfigs.clear();
            siteDimensions.clear();
            clearedSites.clear();

            // 重置Quartz调度器
            try {
                SchedulerManager.reset();
            } catch (Exception e) {
                LOGGER.error("Failed to clear Quartz jobs during reload", e);
            }

            // 重新加载配置
            loadConfigs();

            // 重新安排刷新
            scheduleInitialRefreshes();

            LOGGER.info("矿场配置重载完成，共加载 {} 个矿场", siteConfigs.size());

            // 检查所有矿场状态并清除未激活/未开放的矿场
            checkAllSitesStatus();
        }
    }

    // 检查所有矿场状态
    private static void checkAllSitesStatus() {
        for (String siteName : siteConfigs.keySet()) {
            JsonObject site = siteConfigs.get(siteName);
            // 检查矿场状态
            String status = site.get("status").getAsString();
            if (!"active".equals(status)) {
                if (!clearedSites.contains(siteName)) {
                    LOGGER.info("矿点 {} 未激活，清除区域", siteName);
                    clearSiteArea(siteName);
                    clearedSites.add(siteName);
                }
                continue;
            }
            // 检查是否全天关闭
            boolean isFullDayClose = checkFullClose(site);
            // 检查时间表
            if (isFullDayClose || isSiteCloseNow(site)) {
                // 只在第一次检测到未开放时清除区域
                if (!clearedSites.contains(siteName)) {
                    LOGGER.info("矿点 {} 当前不在开放时间，清除区域", siteName);
                    clearSiteArea(siteName);
                    clearedSites.add(siteName);
                }
            } else {
                // 矿点开放，从已清除集合中移除
                clearedSites.remove(siteName);
            }
        }
    }

    // 检查矿场是否在开放时间内
    private static boolean isSiteCloseNow(JsonObject site) {
        // 获取时间表配置
        JsonArray timeTable = site.getAsJsonArray("timeTable");
        if (timeTable == null || timeTable.isEmpty()) {
            // 没有时间表配置，默认始终开放
            return false;
        }

        // 获取当前时间和星期几
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(DEFAULT_TIME_ZONE));
        DayOfWeek currentDayOfWeek = now.getDayOfWeek();

        // 将Java的DayOfWeek转换为配置文件中的星期几（0=周日，1=周一，...6=周六）
        int configWeekday = currentDayOfWeek == DayOfWeek.SUNDAY ? 0 : currentDayOfWeek.getValue();

        // 查找当前星期几的时间配置
        for (JsonElement element : timeTable) {
            JsonObject entry = element.getAsJsonObject();
            int weekday = entry.get("weekday").getAsInt();

            if (weekday == configWeekday) {
                // 检查是否全天关闭
                if (entry.has("fullDayClose") && entry.get("fullDayClose").getAsBoolean()) {
                    return true; // 全天关闭
                }
                // 解析开始时间和结束时间
                LocalTime startTime = LocalTime.parse(entry.get("startTime").getAsString());
                LocalTime endTime = LocalTime.parse(entry.get("endTime").getAsString());
                LocalTime currentTime = now.toLocalTime();

                // 检查当前时间是否在开放时间段内
                boolean isOpen = !currentTime.isBefore(startTime) && !currentTime.isAfter(endTime);

                // 处理跨午夜的情况（结束时间小于开始时间）
                if (endTime.isBefore(startTime)) {
                    isOpen = !currentTime.isBefore(startTime) || !currentTime.isAfter(endTime);
                }

                return !isOpen;
            }
        }

        // 没有找到当前星期几的配置，默认关闭
        return true;
    }

    // 计算距离下一个开放时间段还有多少秒
    private static long calculateSecondsToNextOpen(JsonObject site) {
        // 获取时间表配置
        JsonArray timeTable = site.getAsJsonArray("timeTable");
        if (timeTable == null || timeTable.isEmpty()) {
            return 0; // 没有时间表，立即开放
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(DEFAULT_TIME_ZONE));
        DayOfWeek currentDayOfWeek = now.getDayOfWeek();
        int currentConfigWeekday = currentDayOfWeek == DayOfWeek.SUNDAY ? 0 : currentDayOfWeek.getValue();

        // 1. 检查今天剩余的时间段
        for (JsonElement element : timeTable) {
            JsonObject entry = element.getAsJsonObject();
            int weekday = entry.get("weekday").getAsInt();

            if (weekday == currentConfigWeekday) {
                // 跳过全天关闭的配置
                if (entry.has("fullDayClose") && entry.get("fullDayClose").getAsBoolean()) {
                    continue;
                }
                LocalTime endTime = LocalTime.parse(entry.get("endTime").getAsString());
                LocalTime currentTime = now.toLocalTime();

                // 如果当前时间在今天的时间段内，但尚未结束
                if (!currentTime.isAfter(endTime)) {
                    ZonedDateTime nextOpen = now.with(endTime).plusSeconds(1);
                    return ChronoUnit.SECONDS.between(now, nextOpen);
                }
            }
        }

        // 2. 检查今天后续的时间段
        for (JsonElement element : timeTable) {
            JsonObject entry = element.getAsJsonObject();
            int weekday = entry.get("weekday").getAsInt();

            if (weekday == currentConfigWeekday) {
                // 跳过全天关闭的配置
                if (entry.has("fullDayClose") && entry.get("fullDayClose").getAsBoolean()) {
                    continue;
                }
                LocalTime startTime = LocalTime.parse(entry.get("startTime").getAsString());
                LocalTime currentTime = now.toLocalTime();

                // 如果今天还有未来的时间段
                if (currentTime.isBefore(startTime)) {
                    return ChronoUnit.SECONDS.between(now, now.with(startTime));
                }
            }
        }

        // 3. 检查明天的开放时间段
        ZonedDateTime tomorrow = now.plusDays(1).with(LocalTime.MIDNIGHT);
        for (JsonElement element : timeTable) {
            JsonObject entry = element.getAsJsonObject();
            int weekday = entry.get("weekday").getAsInt();

            // 计算明天的星期几
            int tomorrowWeekday = (currentConfigWeekday + 1) % 7;

            if (weekday == tomorrowWeekday) {
                // 跳过全天关闭的配置
                if (entry.has("fullDayClose") && entry.get("fullDayClose").getAsBoolean()) {
                    continue;
                }

                LocalTime startTime = LocalTime.parse(entry.get("startTime").getAsString());
                ZonedDateTime nextOpen = tomorrow.with(startTime);
                return ChronoUnit.SECONDS.between(now, nextOpen);
            }
        }

        // 默认返回1小时（理论上不应该发生）
        return 3600;
    }

    // 安排初始刷新
    private static void scheduleInitialRefreshes() {
        for (String siteName : siteConfigs.keySet()) {
            // 立即安排刷新
            scheduleSiteRefresh(siteName);
        }
    }

    public static void forceRefreshSite(String siteName, boolean ignoreTimeTable) {
        JsonObject site = siteConfigs.get(siteName);
        if (site == null) return;

        // 检查矿场状态
        String status = site.get("status").getAsString();
        if (!"active".equals(status)) {
            LOGGER.info("矿场 {} 未激活，跳过强制刷新", siteName);
            return;
        }

        // 检查是否全天关闭（除非忽略时间表）
        if (!ignoreTimeTable) {
            boolean isFullDayClose = checkFullClose(site);

            if (isFullDayClose) {
                LOGGER.info("矿场 {} 今天是全天关闭日，跳过强制刷新", siteName);
                return;
            }
        }

        // 检查时间表（除非忽略）
        if (!ignoreTimeTable && isSiteCloseNow(site)) {
            LOGGER.info("矿场 {} 当前不在开放时间，跳过强制刷新", siteName);
            return;
        }
        // 立即刷新指定矿场
        // 开始倒计时并安排刷新
        startRefreshCountdown(siteName);

        // 延迟10秒执行实际刷新
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                // 在刷新前传送玩家
                teleportPlayersFromSite(siteName);
                // 实际刷新逻辑
                prepareSiteRefresh(siteName);
            }
        }, 10000);
    }

    private static boolean checkFullClose(JsonObject site) {
        boolean isFullDayClose = false;
        JsonArray timeTable = site.getAsJsonArray("timeTable");
        if (timeTable != null) {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(DEFAULT_TIME_ZONE));
            int currentWeekday = now.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : now.getDayOfWeek().getValue();

            for (JsonElement element : timeTable) {
                JsonObject entry = element.getAsJsonObject();
                int weekday = entry.get("weekday").getAsInt();
                if (weekday == currentWeekday) {
                    if (entry.has("fullDayClose") && entry.get("fullDayClose").getAsBoolean()) {
                        isFullDayClose = true;
                    }
                    break;
                }
            }
        }

        return isFullDayClose;
    }

    // 安排矿场刷新
    private static void scheduleSiteRefresh(String siteName) {
        JsonObject site = siteConfigs.get(siteName);
        if (site == null) return;

        // 检查矿场状态
        String status = site.get("status").getAsString();
        if (!"active".equals(status)) {
            LOGGER.info("矿场 {} 未激活，跳过刷新安排", siteName);
            return;
        }

        // 检查时间表
        if (isSiteCloseNow(site)) {
            long secondsToOpen = calculateSecondsToNextOpen(site);
            LOGGER.info("矿场 {} 当前不在开放时间，将在 {} 后安排刷新",
                    siteName, MineSiteUtils.formatDuration(secondsToOpen));

            // 矿场未开放时清除区域
            if (!clearedSites.contains(siteName)) {
                clearSiteArea(siteName);
                clearedSites.add(siteName);
            }

            // 获取下一个开放时间段并安排通知
            Pair<ZonedDateTime, ZonedDateTime> openPeriod = getCurrentOpenTime(site);
            if (openPeriod != null) {
                scheduleNotifications(siteName, openPeriod.getFirst(), openPeriod.getSecond());
            }

            // 安排在下一个开放时间段刷新
            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    scheduleSiteRefresh(siteName);
                }
            }, TimeUnit.SECONDS.toMillis(secondsToOpen));

            return;
        }

        // 矿点开放，从已清除集合中移除
        clearedSites.remove(siteName);

        // 获取当前开放时间段并安排关闭通知
        Pair<ZonedDateTime, ZonedDateTime> openPeriod = getCurrentOpenTime(site);
        if (openPeriod != null) {
            // 只安排关闭通知（开启通知已过期）
            scheduleNotifications(siteName, null, openPeriod.getSecond());
        }

        // 解析刷新间隔
        String refreshInterval = site.get("refreshInterval").getAsString();
        ZonedDateTime nextRefresh = MineSiteUtils.calculateNextRefresh(refreshInterval, DEFAULT_TIME_ZONE);

        // 如果下一个刷新时间已过，立即刷新
        if (nextRefresh.isBefore(ZonedDateTime.now(ZoneId.of(DEFAULT_TIME_ZONE)))) {
            startRefreshCountdown(siteName);
            prepareSiteRefresh(siteName);
        } else {
            // 安排定时刷新
            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    startRefreshCountdown(siteName);
                    prepareSiteRefresh(siteName);
                }
            }, Date.from(nextRefresh.toInstant()));
        }
    }

    // 准备矿场刷新
    private static void prepareSiteRefresh(String siteName) {
        // 延迟10秒执行实际刷新
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                long prepareStart = System.nanoTime();

                // 在刷新前传送玩家
                teleportPlayersFromSite(siteName); // 新增传送逻辑

                JsonObject site = siteConfigs.get(siteName);
                if (site == null) return;

                // 检查矿场状态
                String status = site.get("status").getAsString();
                if (!"active".equals(status)) {
                    LOGGER.info("矿场 {} 未激活，跳过刷新", siteName);
                    return;
                }

                if (isSiteCloseNow(site)) {
                    long secondsToOpen = calculateSecondsToNextOpen(site);
                    LOGGER.info("矿场 {} 当前不在开放时间，将在 {} 后重试",
                            siteName, MineSiteUtils.formatDuration(secondsToOpen));

                    // 只在第一次检测到未开放时清除区域
                    if (!clearedSites.contains(siteName)) {
                        clearSiteArea(siteName);
                        clearedSites.add(siteName);
                    }

                    // 安排在下一个开放时间段刷新
                    Timer timer = new Timer();
                    timer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            prepareSiteRefresh(siteName);
                        }
                    }, TimeUnit.SECONDS.toMillis(secondsToOpen));

                    return;
                }

                // 矿点开放，从已清除集合中移除
                clearedSites.remove(siteName);

                // 解析矿场位置
                BlockPos pos1 = MineSiteUtils.parseBlockPos(site.get("pos1").getAsString());
                BlockPos pos2 = MineSiteUtils.parseBlockPos(site.get("pos2").getAsString());

                // 解析矿石配置
                Map<BlockPos, BlockState> blockStates = new HashMap<>();
                JsonArray mines = site.getAsJsonArray("mines");
                List<WeightedBlock> weightedBlocks = parseWeightedBlocks(mines);

                // 计算区域范围
                int minX = Math.min(pos1.getX(), pos2.getX());
                int minY = Math.min(pos1.getY(), pos2.getY());
                int minZ = Math.min(pos1.getZ(), pos2.getZ());
                int maxX = Math.max(pos1.getX(), pos2.getX());
                int maxY = Math.max(pos1.getY(), pos2.getY());
                int maxZ = Math.max(pos1.getZ(), pos2.getZ());

                // 生成位置队列
                Queue<BlockPos> positions = new LinkedList<>();
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            positions.add(pos);

                            // 随机选择方块状态
                            BlockState state = chooseRandomBlockState(weightedBlocks);
                            blockStates.put(pos, state);
                        }
                    }
                }

                // 存储刷新任务
                siteRefreshQueues.put(siteName, positions);
                siteBlockStates.put(siteName, blockStates);

                // 创建性能指标
                RefreshMetrics metrics = new RefreshMetrics();
                metrics.startTime = System.nanoTime();
                metrics.prepareTime = metrics.startTime - prepareStart;
                metrics.totalBlocks = positions.size();
                refreshMetrics.put(siteName, metrics);

                // 记录准备阶段性能
                LOGGER.info("[PERF] Prepared refresh for site '{}': {} blocks, prepare took {} ms",
                        siteName, positions.size(), MineSiteUtils.nsToMs(metrics.prepareTime));

                // 记录日志
                LOGGER.info("Scheduled refresh for mine site: {} with {} blocks", siteName, positions.size());
            }
        }, 10000); // 10秒延迟
    }

    // 清除矿场区域（设置为空气）
    private static void clearSiteArea(String siteName) {
        JsonObject site = siteConfigs.get(siteName);
        if (site == null) return;

        // 在清除前传送玩家
        teleportPlayersFromSite(siteName);

        // 解析矿场位置
        BlockPos pos1 = MineSiteUtils.parseBlockPos(site.get("pos1").getAsString());
        BlockPos pos2 = MineSiteUtils.parseBlockPos(site.get("pos2").getAsString());

        // 计算区域范围
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        // 生成位置队列
        Queue<BlockPos> positions = new LinkedList<>();
        Map<BlockPos, BlockState> blockStates = new HashMap<>();
        BlockState airState = Blocks.AIR.defaultBlockState();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    positions.add(pos);
                    blockStates.put(pos, airState);
                }
            }
        }

        // 存储清除任务
        siteRefreshQueues.put(siteName, positions);
        siteBlockStates.put(siteName, blockStates);

        LOGGER.info("Scheduled area clear for mine site: {} with {} blocks", siteName, positions.size());
    }

    // 解析权重方块
    private static List<WeightedBlock> parseWeightedBlocks(JsonArray minesArray) {
        List<WeightedBlock> blocks = new ArrayList<>();

        for (JsonElement element : minesArray) {
            JsonObject mine = element.getAsJsonObject();
            String blockId = mine.get("block").getAsString();
            int weight = mine.get("weight").getAsInt();

            Block block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(blockId));
            if (block != null) {
                blocks.add(new WeightedBlock(block.defaultBlockState(), weight));
            }
        }

        // 如果没有配置有效的方块，添加默认石头
        if (blocks.isEmpty()) {
            blocks.add(new WeightedBlock(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 1));
        }

        return blocks;
    }

    // 随机选择方块状态
    private static BlockState chooseRandomBlockState(List<WeightedBlock> weightedBlocks) {
        int totalWeight = weightedBlocks.stream().mapToInt(wb -> wb.weight).sum();
        int random = new Random().nextInt(totalWeight);
        int current = 0;

        for (WeightedBlock wb : weightedBlocks) {
            current += wb.weight;
            if (random < current) {
                return wb.state;
            }
        }

        return weightedBlocks.get(0).state;
    }

    // 处理刷新任务
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        Iterator<Map.Entry<String, Queue<BlockPos>>> iterator = siteRefreshQueues.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Queue<BlockPos>> entry = iterator.next();
            String siteName = entry.getKey();
            Queue<BlockPos> queue = entry.getValue();

            // 获取矿场维度
            ResourceKey<Level> dimension = siteDimensions.get(siteName);
            if (dimension == null) {
                iterator.remove();
                siteBlockStates.remove(siteName);
                continue;
            }

            // 获取世界
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                // 维度未加载，跳过
                continue;
            }

            // 记录当前矿场的tick开始时间
            long siteTickStart = System.nanoTime();

            // 获取方块状态映射
            Map<BlockPos, BlockState> blockStates = siteBlockStates.get(siteName);
            if (blockStates == null) {
                iterator.remove();
                continue;
            }

            // 每tick刷新100个方块
            int blocksPerTick = 100;
            int count = 0;

            while (!queue.isEmpty() && count < blocksPerTick) {
                BlockPos pos = queue.poll();
                BlockState state = blockStates.get(pos);

                if (state != null && level.isLoaded(pos)) {
                    level.setBlock(pos, state, 3);
                    count++;
                }
            }

            // 记录当前矿场的tick耗时
            long siteTickTime = System.nanoTime() - siteTickStart;
            LOGGER.debug("[PERF] Site '{}' processed {} blocks in {} ms ({} blocks/s)",
                    siteName, count, MineSiteUtils.nsToMs(siteTickTime), MineSiteUtils.calculateBlocksPerSecond(count, siteTickTime));

            // 更新性能指标
            RefreshMetrics metrics = refreshMetrics.get(siteName);
            if (metrics != null) {
                metrics.blocksProcessed += count;
                metrics.ticksTaken++;
            }

            // 刷新完成
            if (queue.isEmpty()) {
                iterator.remove();
                siteBlockStates.remove(siteName);

                // 输出完整性能报告
                if (metrics != null) {
                    logPerformanceReport(siteName, metrics);
                }

                server.getPlayerList().broadcastSystemMessage(Component.literal("§a矿场 " + siteName + " 刷新完成！"), false);

                // 记录刷新完成
                LOGGER.info("Completed refresh for mine site: {}", siteName);

                // 安排下一次刷新
                scheduleSiteRefresh(siteName);
            }
        }
    }

    // 通知调度
    private static void scheduleNotifications(String siteName, ZonedDateTime openTime, ZonedDateTime closeTime) {
        if (openTime != null) {
            ZonedDateTime openNotifyTime = openTime.minusMinutes(5);
            if (openNotifyTime.isAfter(ZonedDateTime.now())) {
                SchedulerManager.scheduleNotification(siteName, openNotifyTime, true);
            }
        }

        if (closeTime != null) {
            ZonedDateTime closeNotifyTime = closeTime.minusMinutes(5);
            if (closeNotifyTime.isAfter(ZonedDateTime.now())) {
                SchedulerManager.scheduleNotification(siteName, closeNotifyTime, false);
            }
        }
    }

    private static void startRefreshCountdown(String siteName) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        Timer timer = new Timer();
        AtomicInteger countdown = new AtomicInteger(10);

        // 初始通知（添加传送提示）
        String initialMsg = "§6矿场 " + siteName + " 将在 10 秒后刷新！区域内的玩家将被传送至安全点";
        server.getPlayerList().broadcastSystemMessage(Component.literal(initialMsg), false);

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                int current = countdown.decrementAndGet();
                if (current > 0) {
                    // 不同时间点使用不同颜色
                    String color = current <= 3 ? "§c" : "§6";
                    String message = color + "矿场 " + siteName + " 刷新倒计时: " + current + " 秒！";
                    server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);

                    // 最后3秒额外提示
                    if (current <= 3) {
                        String warning = "§c区域内的玩家即将被传送！";
                        server.getPlayerList().broadcastSystemMessage(Component.literal(warning), false);
                    }
                } else {
                    // 刷新开始通知
                    String finalMsg = "§a矿场 " + siteName + " 正在刷新中...";
                    server.getPlayerList().broadcastSystemMessage(Component.literal(finalMsg), false);
                    this.cancel();
                    timer.cancel();
                }
            }
        }, 1000, 1000); // 1秒后开始，每秒执行
    }

    // 传送矿场内的玩家到安全点
    private static void teleportPlayersFromSite(String siteName) {
        JsonObject site = siteConfigs.get(siteName);
        if (site == null || !site.has("safetyPoint")) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        // 解析安全点坐标
        BlockPos safetyPoint = MineSiteUtils.parseBlockPos(site.get("safetyPoint").getAsString());

        // 获取矿场维度
        ResourceKey<Level> dimension = siteDimensions.get(siteName);
        if (dimension == null) return;

        ServerLevel level = server.getLevel(dimension);
        if (level == null) return;
        // 解析矿场区域
        BlockPos pos1 = MineSiteUtils.parseBlockPos(site.get("pos1").getAsString());
        BlockPos pos2 = MineSiteUtils.parseBlockPos(site.get("pos2").getAsString());

        // 计算区域边界
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        // 查找区域内的玩家
        List<ServerPlayer> playersToTeleport = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() != dimension) continue;

            BlockPos playerPos = player.blockPosition();
            if (playerPos.getX() >= minX && playerPos.getX() <= maxX &&
                    playerPos.getY() >= minY && playerPos.getY() <= maxY &&
                    playerPos.getZ() >= minZ && playerPos.getZ() <= maxZ) {
                playersToTeleport.add(player);
            }
        }

        // 传送玩家
        if (!playersToTeleport.isEmpty()) {
            for (ServerPlayer player : playersToTeleport) {
                player.teleportTo(
                        level,
                        safetyPoint.getX() + 0.5,  // 中心点
                        safetyPoint.getY(),
                        safetyPoint.getZ() + 0.5,  // 中心点
                        player.getYRot(),
                        player.getXRot()
                );

                player.sendSystemMessage(Component.literal(
                        "§e矿场 " + siteName + " 即将刷新，已将你传送至安全区域"
                ));
            }

            LOGGER.info("已将 {} 名玩家从矿场 {} 传送至安全点",
                    playersToTeleport.size(), siteName);
        }
    }

    // 获取当前开放时间段
    private static Pair<ZonedDateTime, ZonedDateTime> getCurrentOpenTime(JsonObject site) {
        JsonArray timeTable = site.getAsJsonArray("timeTable");
        if (timeTable == null || timeTable.isEmpty()) {
            return null;
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(DEFAULT_TIME_ZONE));
        int currentWeekday = now.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : now.getDayOfWeek().getValue();

        // 1. 尝试找到当前星期几的配置
        for (JsonElement element : timeTable) {
            JsonObject entry = element.getAsJsonObject();
            int weekday = entry.get("weekday").getAsInt();

            // 跳过全天关闭的配置
            if (entry.has("fullDayClose") && entry.get("fullDayClose").getAsBoolean()) {
                continue;
            }

            if (weekday == currentWeekday) {
                LocalTime startTime = LocalTime.parse(entry.get("startTime").getAsString());
                LocalTime endTime = LocalTime.parse(entry.get("endTime").getAsString());

                // 处理跨午夜的情况
                boolean crossesMidnight = endTime.isBefore(startTime);

                // 构建开始时间
                ZonedDateTime periodStart = now.with(startTime);
                if (now.toLocalTime().isAfter(startTime) && crossesMidnight) {
                    periodStart = periodStart.plusDays(1);
                }

                // 构建结束时间
                ZonedDateTime periodEnd = now.with(endTime);
                if (crossesMidnight) {
                    periodEnd = periodEnd.isAfter(periodStart) ? periodEnd : periodEnd.plusDays(1);
                }

                return Pair.of(periodStart, periodEnd);
            }
        }
        // 2. 如果当天没有配置，找下一个有配置的日子
        for (int i = 1; i <= 7; i++) {
            ZonedDateTime nextDay = now.plusDays(i);
            int nextWeekday = nextDay.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : nextDay.getDayOfWeek().getValue();

            for (JsonElement element : timeTable) {
                JsonObject entry = element.getAsJsonObject();
                int weekday = entry.get("weekday").getAsInt();

                // 跳过全天关闭的配置
                if (entry.has("fullDayClose") && entry.get("fullDayClose").getAsBoolean()) {
                    continue;
                }

                if (weekday == nextWeekday) {
                    LocalTime startTime = LocalTime.parse(entry.get("startTime").getAsString());
                    LocalTime endTime = LocalTime.parse(entry.get("endTime").getAsString());

                    ZonedDateTime periodStart = nextDay.with(startTime);
                    ZonedDateTime periodEnd = nextDay.with(endTime);

                    // 处理跨午夜
                    if (endTime.isBefore(startTime)) {
                        periodEnd = periodEnd.plusDays(1);
                    }

                    return Pair.of(periodStart, periodEnd);
                }
            }
        }
        return null;
    }

    // 输出完整性能报告
    private static void logPerformanceReport(String siteName, RefreshMetrics metrics) {
        long totalTime = System.nanoTime() - metrics.startTime;
        long activeTime = totalTime - metrics.prepareTime;

        double totalTimeMs = MineSiteUtils.nsToMs(totalTime);
        double activeTimeMs = MineSiteUtils.nsToMs(activeTime);
        double prepareTimeMs = MineSiteUtils.nsToMs(metrics.prepareTime);
        double avgBlocksPerTick = (double) metrics.totalBlocks / metrics.ticksTaken;
        double blocksPerSecond = MineSiteUtils.calculateBlocksPerSecond(metrics.totalBlocks, activeTime);
        double efficiency = (activeTime / (double) totalTime) * 100;

        String totalTimeStr = String.format("%.3f", totalTimeMs);
        String activeTimeStr = String.format("%.3f", activeTimeMs);
        String prepareTimeStr = String.format("%.3f", prepareTimeMs);
        String averageBlocksPerTickStr = String.format("%.1f", avgBlocksPerTick);
        String averageBlocksPerSecondStr = String.format("%.1f", blocksPerSecond);
        String efficiencyStr = String.format("%.2f", efficiency);

        LOGGER.info("[PERF] Refresh completed for site '{}'", siteName);
        LOGGER.info("[PERF]   Total blocks: {}", metrics.totalBlocks);
        LOGGER.info("[PERF]   Total time: {} ms (active: {} ms)", totalTimeStr, activeTimeStr);
        LOGGER.info("[PERF]   Prepare time: {} ms", prepareTimeStr);
        LOGGER.info("[PERF]   Ticks taken: {}", metrics.ticksTaken);
        LOGGER.info("[PERF]   Average blocks/tick: {}", averageBlocksPerTickStr);
        LOGGER.info("[PERF]   Average blocks/second: {}", averageBlocksPerSecondStr);
        LOGGER.info("[PERF]   Efficiency: {}%", efficiencyStr);
    }

    // 权重方块类
    private record WeightedBlock(BlockState state, int weight) {
    }
}
