package com.elibrary.shared.outbox.infrastructure;

import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.outbox.application.OutboxAfterCommitDispatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 建立 Outbox 提交后快速投递所使用的独立线程池。 */
@Configuration(proxyBeanMethods = false)
public class OutboxConfiguration {
    public static final String ROCKETMQ_ENABLED_PROPERTY = "app.outbox.rocketmq-enabled";
    private static final String DIRECT_THREAD_NAME_PREFIX = "outbox-direct-";

    /**
     * 使用固定线程数和有界队列限制快速投递消耗的进程资源。
     *
     * <p>队列中保存的是携带 eventId 的 Runnable，事件正文始终保存在数据库中。
     * 队列饱和时任务会被拒绝，事件保持 NEW，后续由 XXL-JOB 从数据库补偿。</p>
     */
    @Bean(name = OutboxAfterCommitDispatcher.DIRECT_EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor outboxDirectExecutor(AppProperties properties) {
        var outbox = properties.getOutbox();
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(outbox.getDirectThreads());
        executor.setMaxPoolSize(outbox.getDirectThreads());
        executor.setQueueCapacity(outbox.getDirectQueueCapacity());
        executor.setThreadNamePrefix(DIRECT_THREAD_NAME_PREFIX);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
