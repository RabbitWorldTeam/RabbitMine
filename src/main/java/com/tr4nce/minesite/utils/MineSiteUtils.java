package com.tr4nce.minesite.utils;

import net.minecraft.core.BlockPos;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

public class MineSiteUtils {
    // 解析方块位置
    public static BlockPos parseBlockPos(String posStr) {
        String[] parts = posStr.split(",");
        int x = Integer.parseInt(parts[0]);
        int y = Integer.parseInt(parts[1]);
        int z = Integer.parseInt(parts[2]);
        return new BlockPos(x, y, z);
    }

    // 格式化时间间隔
    public static String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            return seconds / 60 + "分钟";
        } else {
            return seconds / 3600 + "小时" + (seconds % 3600) / 60 + "分钟";
        }
    }

    // 计算每秒处理的方块数
    public static double calculateBlocksPerSecond(int blocks, long nanoTime) {
        if (nanoTime == 0) return 0;
        double seconds = nanoTime / 1_000_000_000.0;
        return seconds > 0 ? blocks / seconds : blocks;
    }

    // 纳秒转毫秒
    public static double nsToMs(long nanoTime) {
        return nanoTime / 1_000_000.0;
    }

    // 计算下一次刷新时间
    public static ZonedDateTime calculateNextRefresh(String refreshInterval, String timezone) {
        // 将 refreshInterval 解析为 ZonedDateTime
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezone));
        ZonedDateTime nextRefresh = now.plusSeconds(Integer.parseInt(refreshInterval));
        return nextRefresh.truncatedTo(ChronoUnit.SECONDS);
    }
}
