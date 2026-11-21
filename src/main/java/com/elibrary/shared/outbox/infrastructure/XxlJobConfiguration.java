package com.elibrary.shared.outbox.infrastructure;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在明确启用时建立 XXL-JOB 执行器并向调度中心注册任务。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = XxlJobProperties.PREFIX, name = "enabled", havingValue = "true")
public class XxlJobConfiguration {
    /** 将应用程式设定映射至 XXL-JOB 3.5 执行器。 */
    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties properties) {
        var admin = properties.getAdmin();
        var executorProperties = properties.getExecutor();
        var executor = new XxlJobSpringExecutor();
        executor.setEnabled(true);
        executor.setAdminAddresses(admin.getAddresses());
        executor.setAccessToken(admin.getAccessToken());
        executor.setTimeout(admin.getTimeoutSeconds());
        executor.setAppname(executorProperties.getAppName());
        executor.setAddress(executorProperties.getAddress());
        executor.setIp(executorProperties.getIp());
        executor.setPort(executorProperties.getPort());
        executor.setLogPath(executorProperties.getLogPath());
        executor.setLogRetentionDays(executorProperties.getLogRetentionDays());
        executor.setGlueEnabled(false);
        return executor;
    }
}
