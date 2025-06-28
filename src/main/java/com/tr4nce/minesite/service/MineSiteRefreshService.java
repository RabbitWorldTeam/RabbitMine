package com.tr4nce.minesite.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.tr4nce.minesite.MineSite;
import com.tr4nce.minesite.config.Config;
import com.tr4nce.minesite.utils.MineSiteUtils;
import net.minecraft.commands.CommandSourceStack;
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

            // 重新加载配置
            loadConfigs();

            LOGGER.info("矿场配置重载完成，共加载 {} 个矿场", siteConfigs.size());
        }
    }
    /**
     * 安排延迟刷新矿场
     * @param siteName 矿场名称
     * @param delaySeconds 延迟时间（秒）
     */
    public static void scheduleRefreshWithDelay(String siteName, long delaySeconds) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        String initialMsg = "§6矿场 " + siteName + " 将在 " + MineSiteUtils.secondsToTime(delaySeconds+10) + " 后刷新！请注意有序离开矿场区域。";
        server.getPlayerList().broadcastSystemMessage(Component.literal(initialMsg), false);

        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                startRefreshCountdown(siteName);
                // 延迟10秒执行实际刷新
                Timer refreshTimer = new Timer();
                refreshTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        teleportPlayersFromSite(siteName);
                        prepareSiteRefresh(siteName);
                    }
                }, 10000);
            }
        }, delaySeconds * 1000);

        LOGGER.info("已安排矿场 {} 在 {} 秒后刷新", siteName, delaySeconds);
    }

    public static void scheduleSiteOpenOrClose(String siteName, boolean open, int delaySeconds) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        CommandSourceStack source = server.createCommandSourceStack()
                .withPermission(4);
        String initialMsg;
        if (open) {
            // 安排矿场开启
            initialMsg = "§6矿场 " + siteName + " 将在 " + MineSiteUtils.secondsToTime(delaySeconds) + " 后开启！请不要在矿场区域内逗留。";
        } else {
            // 安排矿场关闭
            initialMsg = "§6矿场 " + siteName + " 将在 " + MineSiteUtils.secondsToTime(delaySeconds) + " 后关闭！请尽快离开矿场区域。";
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal(initialMsg), false);

        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                String command = "minesite " + (open ? "enable" : "disable") + " " + siteName;
                // 延迟10秒执行实际刷新
                Timer refreshTimer = new Timer();
                refreshTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        server.getCommands().performPrefixedCommand(source, command);
                    }
                }, 10000);
            }
        }, delaySeconds * 1000L);
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

    // 准备矿场刷新
    public static void prepareSiteRefresh(String siteName) {
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
                MineSiteUtils.RegionBounds mineArea = MineSiteUtils.RegionBounds.fromCorners(pos1, pos2);

                // 生成位置队列
                Queue<BlockPos> positions = new LinkedList<>();
                for (int x = mineArea.getMinX(); x <= mineArea.getMaxX(); x++) {
                    for (int y = mineArea.getMinY(); y <= mineArea.getMaxY(); y++) {
                        for (int z = mineArea.getMinZ(); z <= mineArea.getMaxZ(); z++) {
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
    public static void clearSiteArea(String siteName) {
        JsonObject site = siteConfigs.get(siteName);
        if (site == null) return;

        // 在清除前传送玩家
        teleportPlayersFromSite(siteName);

        // 解析矿场位置
        BlockPos pos1 = MineSiteUtils.parseBlockPos(site.get("pos1").getAsString());
        BlockPos pos2 = MineSiteUtils.parseBlockPos(site.get("pos2").getAsString());

        // 计算区域范围
        MineSiteUtils.RegionBounds mineArea = MineSiteUtils.RegionBounds.fromCorners(pos1, pos2);

        // 生成位置队列
        Queue<BlockPos> positions = new LinkedList<>();
        Map<BlockPos, BlockState> blockStates = new HashMap<>();
        BlockState airState = Blocks.AIR.defaultBlockState();

        for (int x = mineArea.getMinX(); x <= mineArea.getMaxX(); x++) {
            for (int y = mineArea.getMinY(); y <= mineArea.getMaxY(); y++) {
                for (int z = mineArea.getMinZ(); z <= mineArea.getMaxZ(); z++) {
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

            // 每tick刷新一定数量的方块
            int blocksPerTick = Config.SITE_REFRESH_SPEED.get() > 0 ? Config.SITE_REFRESH_SPEED.get() : 100;
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

        // 计算区域范围
        MineSiteUtils.RegionBounds mineArea = MineSiteUtils.RegionBounds.fromCorners(pos1, pos2);

        // 查找区域内的玩家
        List<ServerPlayer> playersToTeleport = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() != dimension) continue;

            BlockPos playerPos = player.blockPosition();
            if (playerPos.getX() >= mineArea.getMinX() && playerPos.getX() <= mineArea.getMaxX() &&
                    playerPos.getY() >= mineArea.getMinY() && playerPos.getY() <= mineArea.getMaxY() &&
                    playerPos.getZ() >= mineArea.getMinZ() && playerPos.getZ() <= mineArea.getMaxZ()) {
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
