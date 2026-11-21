package com.elibrary.shared.kernel;

import java.time.Instant;

/**
 * 提供可替换的系统时间来源。
 * 业务服务依赖此介面，测试即可注入固定时间而不依赖真实时钟。
 */
public interface ClockProvider {
    /** 取得目前的 UTC 时间点。 */
    Instant now();
}
