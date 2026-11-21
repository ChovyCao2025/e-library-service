package com.elibrary.shared.outbox.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** XXL-JOB 调度中心与本服务执行器的型别化设定。 */
@ConfigurationProperties(XxlJobProperties.PREFIX)
@Getter
public class XxlJobProperties {
    public static final String PREFIX = "xxl.job";

    @Setter
    private boolean enabled;
    private Admin admin = new Admin();
    private Executor executor = new Executor();

    public void setAdmin(Admin admin) {
        this.admin = admin == null ? new Admin() : admin;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor == null ? new Executor() : executor;
    }

    /** 调度中心的注册位址、存取权杖及连线逾时。 */
    @Getter
    public static class Admin {
        private static final int DEFAULT_TIMEOUT_SECONDS = 3;

        private String addresses = "";
        private String accessToken = "";
        private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

        public void setAddresses(String addresses) {
            this.addresses = addresses == null ? "" : addresses;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken == null ? "" : accessToken;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        }
    }

    /** 执行器注册资讯与 XXL-JOB 任务日志保留策略。 */
    @Getter
    public static class Executor {
        private static final String DEFAULT_APP_NAME = "e-library-outbox";
        private static final int DEFAULT_PORT = 9999;
        private static final String DEFAULT_LOG_PATH = "./logs/xxl-job";
        private static final int DEFAULT_LOG_RETENTION_DAYS = 30;

        private String appName = DEFAULT_APP_NAME;
        private String address = "";
        private String ip = "";
        private int port = DEFAULT_PORT;
        private String logPath = DEFAULT_LOG_PATH;
        private int logRetentionDays = DEFAULT_LOG_RETENTION_DAYS;

        public void setAppName(String appName) {
            this.appName = appName == null ? DEFAULT_APP_NAME : appName;
        }

        public void setAddress(String address) {
            this.address = address == null ? "" : address;
        }

        public void setIp(String ip) {
            this.ip = ip == null ? "" : ip;
        }

        public void setPort(int port) {
            this.port = port <= 0 ? DEFAULT_PORT : port;
        }

        public void setLogPath(String logPath) {
            this.logPath = logPath == null ? DEFAULT_LOG_PATH : logPath;
        }

        public void setLogRetentionDays(int logRetentionDays) {
            this.logRetentionDays = logRetentionDays <= 0
                    ? DEFAULT_LOG_RETENTION_DAYS : logRetentionDays;
        }
    }
}
