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
    private Search search = new Search();
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
    public void setSearch(Search search) { this.search = search == null ? new Search() : search; }

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

    /** Elasticsearch 书目搜索、回填和消息消费设定。 */
    @Getter
    @NoArgsConstructor
    public static class Search {
        private static final String DEFAULT_PHYSICAL_INDEX = "catalog-book-v1-000001";
        private static final String DEFAULT_READ_ALIAS = "catalog-book-read";
        private static final String DEFAULT_WRITE_ALIAS = "catalog-book-write";
        private static final String DEFAULT_CONSUMER_GROUP = "catalog-search-indexer";
        private static final int DEFAULT_BATCH_SIZE = 500;
        private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
        private static final Duration DEFAULT_AWAIT_DURATION = Duration.ofSeconds(5);
        private static final Duration DEFAULT_INVISIBLE_DURATION = Duration.ofSeconds(30);

        @Setter
        private boolean enabled;
        @Setter
        private boolean rebuildOnStartup;
        @Setter
        private boolean consumerEnabled;
        private String physicalIndex = DEFAULT_PHYSICAL_INDEX;
        private String readAlias = DEFAULT_READ_ALIAS;
        private String writeAlias = DEFAULT_WRITE_ALIAS;
        private String consumerGroup = DEFAULT_CONSUMER_GROUP;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private Duration pollInterval = DEFAULT_POLL_INTERVAL;
        private Duration awaitDuration = DEFAULT_AWAIT_DURATION;
        private Duration invisibleDuration = DEFAULT_INVISIBLE_DURATION;

        public void setPhysicalIndex(String physicalIndex) {
            this.physicalIndex = defaultIfBlank(physicalIndex, DEFAULT_PHYSICAL_INDEX);
        }
        public void setReadAlias(String readAlias) {
            this.readAlias = defaultIfBlank(readAlias, DEFAULT_READ_ALIAS);
        }
        public void setWriteAlias(String writeAlias) {
            this.writeAlias = defaultIfBlank(writeAlias, DEFAULT_WRITE_ALIAS);
        }
        public void setConsumerGroup(String consumerGroup) {
            this.consumerGroup = defaultIfBlank(consumerGroup, DEFAULT_CONSUMER_GROUP);
        }
        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
        }
        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = defaultDuration(pollInterval, DEFAULT_POLL_INTERVAL);
        }
        public void setAwaitDuration(Duration awaitDuration) {
            this.awaitDuration = defaultDuration(awaitDuration, DEFAULT_AWAIT_DURATION);
        }
        public void setInvisibleDuration(Duration invisibleDuration) {
            this.invisibleDuration = defaultDuration(invisibleDuration, DEFAULT_INVISIBLE_DURATION);
        }

        private static String defaultIfBlank(String value, String defaultValue) {
            return value == null || value.isBlank() ? defaultValue : value.strip();
        }

        private static Duration defaultDuration(Duration value, Duration defaultValue) {
            return value == null || value.isNegative() || value.isZero() ? defaultValue : value;
        }
    }
}
