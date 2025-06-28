package com.tr4nce.minesite.utils;

import net.minecraft.core.BlockPos;

public class MineSiteUtils {
    // 解析方块位置
    public static BlockPos parseBlockPos(String posStr) {
        String[] parts = posStr.split(",");
        int x = Integer.parseInt(parts[0]);
        int y = Integer.parseInt(parts[1]);
        int z = Integer.parseInt(parts[2]);
        return new BlockPos(x, y, z);
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

    // 秒换算
    public static String secondsToTime(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return String.format("%d小时%d分钟%d秒", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%d分钟%d秒", minutes, secs);
        } else {
            return String.format("%d秒", secs);
        }
    }

    public static class RegionBounds {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int maxX;
        private final int maxY;
        private final int maxZ;
        // 私有构造方法
        private RegionBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            // 验证参数有效性
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("Min values must be less than or equal to max values");
            }

            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
        // 工厂方法：从两个对角点创建区域
        public static RegionBounds fromCorners(BlockPos corner1, BlockPos corner2) {
            return new RegionBounds(
                    Math.min(corner1.getX(), corner2.getX()),
                    Math.min(corner1.getY(), corner2.getY()),
                    Math.min(corner1.getZ(), corner2.getZ()),
                    Math.max(corner1.getX(), corner2.getX()),
                    Math.max(corner1.getY(), corner2.getY()),
                    Math.max(corner1.getZ(), corner2.getZ())
            );
        }
        // Getter方法
        public int getMinX() { return minX; }
        public int getMinY() { return minY; }
        public int getMinZ() { return minZ; }
        public int getMaxX() { return maxX; }
        public int getMaxY() { return maxY; }
        public int getMaxZ() { return maxZ; }
    }
}
