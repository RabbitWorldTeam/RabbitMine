package com.tr4nce.minesite.notification;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.time.ZonedDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SchedulerManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ScheduledExecutorService scheduler;
    private static final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private static final AtomicInteger threadCounter = new AtomicInteger(0);

    static {
        initializeScheduler();
    }

    private static void initializeScheduler() {
        scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread thread = new Thread(r, "mine-site-scheduler-" + threadCounter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        LOGGER.info("Native scheduler initialized");
    }

    public static void clearAllJobs() {
        scheduledTasks.forEach((name, future) -> {
            if (future != null && !future.isDone()) {
                future.cancel(false);
                LOGGER.debug("Cancelled task: {}", name);
            }
        });
        scheduledTasks.clear();
        LOGGER.info("Cleared all scheduled tasks");
    }

    public static void reset() {
        clearAllJobs();
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("Scheduler did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        initializeScheduler();
        LOGGER.info("Scheduler reset completed");
    }

    public static void scheduleNotification(String siteName, ZonedDateTime notifyTime, boolean isOpenNotification) {
        String taskName = (isOpenNotification ? "open_notify_" : "close_notify_") + siteName;

        // 取消已存在的同名任务
        ScheduledFuture<?> existingTask = scheduledTasks.get(taskName);
        if (existingTask != null) {
            existingTask.cancel(false);
            LOGGER.debug("Cancelled duplicate task: {}", taskName);
        }
        long delay = calculateDelay(notifyTime);
        Runnable task = new NotificationTask(siteName, isOpenNotification);

        ScheduledFuture<?> future = scheduler.schedule(
                task,
                delay,
                TimeUnit.MILLISECONDS
        );

        scheduledTasks.put(taskName, future);
        LOGGER.info("Scheduled notification '{}' with {}ms delay", taskName, delay);
    }

    private static long calculateDelay(ZonedDateTime notifyTime) {
        long now = System.currentTimeMillis();
        long targetTime = notifyTime.toInstant().toEpochMilli();
        return Math.max(0, targetTime - now);
    }
}
