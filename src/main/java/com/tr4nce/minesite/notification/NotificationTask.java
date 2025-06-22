package com.tr4nce.minesite.notification;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

public class NotificationTask implements Runnable {
    private final String siteName;
    private final boolean isOpenNotification;

    public NotificationTask(String siteName, boolean isOpenNotification) {
        this.siteName = siteName;
        this.isOpenNotification = isOpenNotification;
    }

    @Override
    public void run() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return; // 服务器未运行
        }
        String message = isOpenNotification
                ? "§a矿场 " + siteName + " 将在5分钟后开启！"
                : "§c矿场 " + siteName + " 将在5分钟后关闭！";
        // 在主线程执行广播
        server.execute(() ->
                server.getPlayerList().broadcastSystemMessage(Component.literal(message), false)
        );
    }
}