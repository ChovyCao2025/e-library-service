package com.elibrary.shared.kernel;

import com.elibrary.shared.web.security.UserRole;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 集中管理具型别的应用程式设定，并在赋值时套用安全预设值。 */
@ConfigurationProperties("app")
@Getter
@NoArgsConstructor
public class AppProperties {
    private static final int MIN_NODE_ID = 0;
    private static final int MAX_NODE_ID = 1023;

    private ApplicationRole role = ApplicationRole.ALL;
    private Auth auth = new Auth();
    private Lending lending = new Lending();
    private Outbox outbox = new Outbox();
    private int nodeId;

    public AppProperties(ApplicationRole role, Auth auth, Lending lending, Outbox outbox, int nodeId) {
        this.role = role == null ? ApplicationRole.ALL : role;
        setAuth(auth);
        setLending(lending);
        setOutbox(outbox);
        setNodeId(nodeId);
    }

    public void setRole(ApplicationRole role) {
        this.role = role == null ? ApplicationRole.ALL : role;
    }

    public void setAuth(Auth auth) { this.auth = auth == null ? new Auth() : auth; }
    public void setLending(Lending lending) { this.lending = lending == null ? new Lending() : lending; }
    public void setOutbox(Outbox outbox) { this.outbox = outbox == null ? new Outbox() : outbox; }

    public void setNodeId(int nodeId) {
        this.nodeId = Math.max(MIN_NODE_ID, Math.min(nodeId, MAX_NODE_ID));
    }

    /** 身分验证与本机开发预设值。 */
    @Getter
    @NoArgsConstructor
    public static class Auth {
        @Setter
        private boolean devMode;
        @Setter
        private long defaultUserId;
        @Setter
        private UserRole defaultRole = UserRole.USER;
    }

    /** 借阅政策与到期归还排程设定。 */
    @Getter
    @NoArgsConstructor
    public static class Lending {
        private static final int DEFAULT_MAX_ACTIVE_LOANS = 5;
        private static final int DEFAULT_LOAN_DAYS = 14;
        private static final String DEFAULT_POLICY_VERSION = "v1";
        private static final Duration DEFAULT_EXPIRY_INTERVAL = Duration.ofSeconds(30);
        private static final int DEFAULT_EXPIRY_BATCH = 500;

        private int maxActiveLoans = DEFAULT_MAX_ACTIVE_LOANS;
        private int loanDays = DEFAULT_LOAN_DAYS;
        private String policyVersion = DEFAULT_POLICY_VERSION;
        private Duration expiryInterval = DEFAULT_EXPIRY_INTERVAL;
        private int expiryBatch = DEFAULT_EXPIRY_BATCH;

        public void setMaxActiveLoans(int maxActiveLoans) {
            this.maxActiveLoans = maxActiveLoans <= 0 ? DEFAULT_MAX_ACTIVE_LOANS : maxActiveLoans;
        }
        public void setLoanDays(int loanDays) {
            this.loanDays = loanDays <= 0 ? DEFAULT_LOAN_DAYS : loanDays;
        }
        public void setPolicyVersion(String policyVersion) {
            this.policyVersion = policyVersion == null ? DEFAULT_POLICY_VERSION : policyVersion;
        }
        public void setExpiryInterval(Duration expiryInterval) {
            this.expiryInterval = expiryInterval == null ? DEFAULT_EXPIRY_INTERVAL : expiryInterval;
        }
        public void setExpiryBatch(int expiryBatch) {
            this.expiryBatch = expiryBatch <= 0 ? DEFAULT_EXPIRY_BATCH : expiryBatch;
        }
    }

    /** Outbox 投递租约、批次、直发执行绪与 RocketMQ 设定。 */
    @Getter
    @NoArgsConstructor
    public static class Outbox {
        private static final int DEFAULT_BATCH_SIZE = 200;
        private static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);
        private static final int DEFAULT_DIRECT_THREADS = 2;
        private static final int DEFAULT_DIRECT_QUEUE_CAPACITY = 500;

        private int batchSize = DEFAULT_BATCH_SIZE;
        private Duration lease = DEFAULT_LEASE;
        private int directThreads = DEFAULT_DIRECT_THREADS;
        private int directQueueCapacity = DEFAULT_DIRECT_QUEUE_CAPACITY;
        @Setter
        private boolean rocketmqEnabled;
        @Setter
        private String endpoints;

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
        }
        public void setLease(Duration lease) {
            this.lease = lease == null ? DEFAULT_LEASE : lease;
        }
        public void setDirectThreads(int directThreads) {
            this.directThreads = directThreads <= 0 ? DEFAULT_DIRECT_THREADS : directThreads;
        }
        public void setDirectQueueCapacity(int directQueueCapacity) {
            this.directQueueCapacity = directQueueCapacity <= 0
                    ? DEFAULT_DIRECT_QUEUE_CAPACITY : directQueueCapacity;
        }
    }
}
