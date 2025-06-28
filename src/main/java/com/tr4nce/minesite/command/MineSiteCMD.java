package com.tr4nce.minesite.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import com.tr4nce.minesite.MineSite;
import com.tr4nce.minesite.utils.RegionSelector;
import com.tr4nce.minesite.config.Config;
import com.tr4nce.minesite.service.MineSiteConfigService;
import com.tr4nce.minesite.service.MineSiteRefreshService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public class MineSiteCMD {
    private static final Logger LOGGER = LogUtils.getLogger();

    static {
        // 初始化配置管理器
        MineSiteConfigService.init(MineSite.MODID);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("minesite")
                        .requires(source -> source.hasPermission(2)) // 需要权限2
                        .then(Commands.literal("list")
                                .executes(MineSiteCMD::listSites)
                        )

                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(MineSiteCMD::createSite)
                                )
                        )

                        .then(Commands.literal("enable")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(MineSiteCMD::enableSite)
                                )
                        )

                        .then(Commands.literal("disable")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(MineSiteCMD::disableSite)
                                )
                        )

                        .then(Commands.literal("delete")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(MineSiteCMD::deleteSite)
                                )
                        )

                        .then(Commands.literal("refresh")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(MineSiteCMD::refreshSite)
                                ))

                        .then(Commands.literal("setSafetyPoint")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(MineSiteCMD::setSafetyPoint)
                                )
                        )

                        .then(Commands.literal("reload")
                                .executes(MineSiteCMD::reloadConfig)
                        )

                        .then(Commands.literal("help")
                                .executes(MineSiteCMD::showHelp)
                        )
        );

    }

    private static int createSite(CommandContext<CommandSourceStack> ctx) {
        // 只能由玩家执行
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§c此命令只能由玩家执行"));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        String creator = getCreatorName(ctx.getSource());

        // 检查是否选择了两个位置
        RegionSelector.SelectionData selection = RegionSelector.getSelectionData(player);
        if (selection == null) {
            ctx.getSource().sendFailure(Component.literal("§c请先使用选择工具设置两个位置!"));
            ctx.getSource().sendFailure(Component.literal("§7手持 §e" + Config.SELECTION_TOOL.get() + " §7左键和右键点击方块"));
            return 0;
        }

        if (MineSiteConfigService.addNewSite(name, creator, selection.pos1, selection.pos2, selection.getDimensionName())) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("§a成功创建矿场: " + name + "\n§7请编辑配置文件完善细节"),
                    false
            );
            return Command.SINGLE_SUCCESS;
        } else {
            ctx.getSource().sendFailure(
                    Component.literal("§c错误: 矿场名称 '" + name + "' 已存在")
            );
            return 0;
        }
    }

    private static int listSites(CommandContext<CommandSourceStack> ctx) {
        try {
            // 获取完整配置对象
            JsonObject config = MineSiteConfigService.getFullConfig();
            JsonArray sites = config.getAsJsonArray("sites");

            // 检查是否有矿场配置
            if (sites.isEmpty()) {
                ctx.getSource().sendSuccess(
                        () -> Component.literal("§6当前没有配置任何矿场"),
                        false
                );
                return Command.SINGLE_SUCCESS;
            }
            // 构建信息表格
            StringBuilder sb = getStringBuilder(sites);

            // 发送结果
            ctx.getSource().sendSuccess(
                    () -> Component.literal(sb.toString()),
                    false
            );

            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§c无法读取配置文件: " + e.getMessage()));
            LOGGER.error("Failed to list sites", e);
            return 0;
        }
    }

    private static int enableSite(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        // 启用矿场
        if (MineSiteConfigService.enableSite(name)) {
            try {
                // 重载配置文件确保内存缓存更新
                MineSiteConfigService.forceReloadConfig();

                // 重载矿场刷新服务
                MineSiteRefreshService.reloadSites();

                // 清除矿场区域
                MineSiteRefreshService.prepareSiteRefresh(name);

                ctx.getSource().sendSuccess(
                        () -> Component.literal("§a成功启用矿场: " + name),
                        false
                );
                return Command.SINGLE_SUCCESS;
            } catch (Exception e) {
                ctx.getSource().sendFailure(
                        Component.literal("§c启用矿场后重载失败: " + e.getMessage())
                );
                LOGGER.error("Failed to reload after enabling site", e);
                return 0;
            }
        } else {
            ctx.getSource().sendFailure(
                    Component.literal("§c错误: 矿场 '" + name + "' 不存在")
            );
            return 0;
        }
    }

    private static int disableSite(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        // 禁用矿场
        if (MineSiteConfigService.disableSite(name)) {
            try {
                // 重载配置文件确保内存缓存更新
                MineSiteConfigService.forceReloadConfig();

                // 重载矿场刷新服务
                MineSiteRefreshService.reloadSites();

                // 清除矿场区域
                MineSiteRefreshService.clearSiteArea(name);

                ctx.getSource().sendSuccess(
                        () -> Component.literal("§a成功禁用矿场: " + name),
                        false
                );
                return Command.SINGLE_SUCCESS;
            } catch (Exception e) {
                ctx.getSource().sendFailure(
                        Component.literal("§c禁用矿场后重载失败: " + e.getMessage())
                );
                LOGGER.error("Failed to reload after disabling site", e);
                return 0;
            }
        } else {
            ctx.getSource().sendFailure(
                    Component.literal("§c错误: 矿场 '" + name + "' 不存在")
            );
            return 0;
        }
    }

    private static int deleteSite(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        // 删除矿场
        if (MineSiteConfigService.deleteSite(name)) {
            try {
                // 重载配置文件确保内存缓存更新
                MineSiteConfigService.forceReloadConfig();

                // 重载矿场刷新服务
                MineSiteRefreshService.reloadSites();

                ctx.getSource().sendSuccess(
                        () -> Component.literal("§a成功删除矿场: " + name),
                        false
                );
                return Command.SINGLE_SUCCESS;
            } catch (Exception e) {
                ctx.getSource().sendFailure(
                        Component.literal("§c删除矿场后重载失败: " + e.getMessage())
                );
                LOGGER.error("Failed to reload after deletion", e);
                return 0;
            }
        } else {
            ctx.getSource().sendFailure(
                    Component.literal("§c错误: 矿场 '" + name + "' 不存在")
            );
            return 0;
        }
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        try {
            // 强制重载配置文件
            MineSiteConfigService.forceReloadConfig();

            // 重载矿场刷新服务
            MineSiteRefreshService.reloadSites();

            ctx.getSource().sendSuccess(
                    () -> Component.literal("§a配置文件已成功重载！"),
                    false
            );
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            ctx.getSource().sendFailure(
                    Component.literal("§c配置文件重载失败: " + e.getMessage())
            );
            LOGGER.error("Failed to reload config", e);
            return 0;
        }
    }

    private static int refreshSite(CommandContext<CommandSourceStack> ctx) {
        String siteName = StringArgumentType.getString(ctx, "name");
        System.out.println(siteName);

        boolean ignoreTimeTable = true;

        MineSiteRefreshService.forceRefreshSite(siteName, ignoreTimeTable);
        ctx.getSource().sendSuccess(
                () -> Component.literal("§a已安排刷新矿场: " + siteName),
                false
        );
        return Command.SINGLE_SUCCESS;
    }

    private static int setSafetyPoint(CommandContext<CommandSourceStack> ctx) {
        // 只能由玩家执行
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§c此命令只能由玩家执行"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");

        // 获取玩家当前位置
        BlockPos playerPos = player.blockPosition();
        String safetyPoint = String.format("%d,%d,%d",
                playerPos.getX(), playerPos.getY(), playerPos.getZ());

        // 尝试更新安全点配置
        if (MineSiteConfigService.setSafetyPoint(name, safetyPoint)) {
            // 重载配置以确保更新生效
            try {
                MineSiteConfigService.forceReloadConfig();
                MineSiteRefreshService.reloadSites();
            } catch (Exception e) {
                ctx.getSource().sendFailure(
                        Component.literal("§c设置安全点后重载失败: " + e.getMessage())
                );
                LOGGER.error("Failed to reload after setting safety point", e);
                return 0;
            }

            ctx.getSource().sendSuccess(
                    () -> Component.literal("§a成功设置矿场 '" + name + "' 的安全点为: " + safetyPoint),
                    false
            );
            return Command.SINGLE_SUCCESS;
        } else {
            ctx.getSource().sendFailure(
                    Component.literal("§c错误: 矿场 '" + name + "' 不存在或配置更新失败")
            );
            return 0;
        }
    }

    private static int showHelp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
                () -> Component.literal("§6MineSite 命令帮助:\n" +
                        "§a/minesite create <名称> §7- 创建新矿场\n" +
                        "§a/minesite delete <名称> §7- 删除指定矿场\n" +
                        "§a/minesite enable <名称> §7- 启用指定矿场\n" +
                        "§a/minesite disable <名称> §7- 禁用指定矿场\n" +
                        "§a/minesite list §7- 查看所有矿场\n" +
                        "§a/minesite reload §7- §b重载配置文件§7（热重载）\n" +
                        "§a/minesite refresh <名称> §7- 立即刷新指定矿场\n" +
                        "§a/minesite help §7- 显示此帮助信息\n" +
                        "§b区域选择工具: §e" + Config.SELECTION_TOOL.get() + "\n"),
                false
        );
        return Command.SINGLE_SUCCESS;
    }

    private static String getCreatorName(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player.getScoreboardName();
        }
        return "系统";
    }

    private static @NotNull StringBuilder getStringBuilder(JsonArray sites) {
        StringBuilder sb = new StringBuilder();
        sb.append("§6矿场列表 (名称 | 状态 | 创建者):\n");
        sb.append("§e================================\n");

        // 遍历所有矿场
        for (JsonElement element : sites) {
            JsonObject site = element.getAsJsonObject();
            String name = site.get("name").getAsString();
            String status = site.get("status").getAsString();
            String creator = site.get("creator").getAsString();

            // 格式化状态显示
            String statusDisplay = status.equals("active") ? "§a已开启" : "§c已关闭";

            // 添加到结果
            sb.append(String.format("§b%-12s §7| %-8s §7| §e%s\n",
                    name, statusDisplay, creator));
        }
        return sb;
    }
}
