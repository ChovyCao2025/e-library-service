package com.elibrary.shared.kernel;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 產生 64 位元趨勢遞增 ID：時間戳 41 位、節點 10 位、同毫秒序號 12 位。
 * 方法以同步區段保護單程序內的時間與序號狀態。
 */
@Component
public class SnowflakeIdGenerator implements IdGenerator {
    private static final long EPOCH_MILLIS = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
    private static final long SEQUENCE_MASK = 0xFFFL;

    private final long nodeId;
    private long lastMillis = -1;
    private long sequence;

    public SnowflakeIdGenerator(AppProperties properties) {
        this.nodeId = properties.nodeId();
    }

    @Override
    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < lastMillis) {
            // 時鐘回撥時停止發號，避免產生與既有資料重複的 ID。
            throw new IllegalStateException("System clock moved backwards");
        }
        if (now == lastMillis) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 單毫秒 4096 個序號耗盡時等待下一毫秒，不跨時間片重複使用序號。
                now = waitForNextMillis(lastMillis);
            }
        } else {
            sequence = 0;
        }
        lastMillis = now;
        return ((now - EPOCH_MILLIS) << 22) | (nodeId << 12) | sequence;
    }

    private static long waitForNextMillis(long previous) {
        long now;
        do {
            Thread.onSpinWait();
            now = System.currentTimeMillis();
        } while (now <= previous);
        return now;
    }
}
