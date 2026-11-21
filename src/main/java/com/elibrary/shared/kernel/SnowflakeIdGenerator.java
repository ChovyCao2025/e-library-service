package com.elibrary.shared.kernel;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 产生 64 位元趋势递增 ID：时间戳 41 位、节点 10 位、同毫秒序号 12 位。
 * 方法以同步区段保护单程序内的时间与序号状态。
 */
@Component
public class SnowflakeIdGenerator {
    private static final long EPOCH_MILLIS = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
    private static final long SEQUENCE_MASK = 0xFFFL;

    private final long nodeId;
    private long lastMillis = -1;
    private long sequence;

    public SnowflakeIdGenerator(AppProperties properties) {
        this.nodeId = properties.getNodeId();
    }

    /**
     * 产生下一个 ID。同毫秒内递增序号，序号耗尽时等待下一毫秒；
     * 侦测到时钟回拨则拒绝发号，避免重复。
     */
    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < lastMillis) {
            // 时钟回拨时停止发号，避免产生与既有资料重复的 ID。
            throw new IllegalStateException("System clock moved backwards");
        }
        if (now == lastMillis) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 单毫秒 4096 个序号耗尽时等待下一毫秒，不跨时间片重复使用序号。
                now = waitForNextMillis(lastMillis);
            }
        } else {
            sequence = 0;
        }
        lastMillis = now;
        return ((now - EPOCH_MILLIS) << 22) | (nodeId << 12) | sequence;
    }

    /** 忙等至下一毫秒，仅在单节点单毫秒序号空间耗尽时触发。 */
    private static long waitForNextMillis(long previous) {
        long now;
        do {
            Thread.onSpinWait();
            now = System.currentTimeMillis();
        } while (now <= previous);
        return now;
    }
}
