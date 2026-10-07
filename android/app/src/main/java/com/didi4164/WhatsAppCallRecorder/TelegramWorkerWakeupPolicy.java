package com.didi4164.WhatsAppCallRecorder;

/** A worker time-budget yield must retain known pending work, without reviving stale authority. */
public final class TelegramWorkerWakeupPolicy {
    private TelegramWorkerWakeupPolicy() {}
    public static boolean shouldRetryAfterYield(boolean stopped, boolean currentDestination, boolean pending) {
        return !stopped && currentDestination && pending;
    }
}
