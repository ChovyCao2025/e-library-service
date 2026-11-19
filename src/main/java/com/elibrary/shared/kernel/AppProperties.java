package com.elibrary.shared.kernel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 集中管理具型別的應用程式設定，並在 compact constructor 套用安全預設值。 */
@ConfigurationProperties("app")
public record AppProperties(
        String role,
        Auth auth,
        Lending lending,
        Outbox outbox,
        Cursor cursor,
        int nodeId
) {
    public AppProperties {
        role = role == null ? "all" : role;
        nodeId = Math.max(0, Math.min(nodeId, 1023));
    }

    public record Auth(boolean devMode, long defaultUserId, String defaultRole) {
        public Auth {
            defaultRole = defaultRole == null ? "USER" : defaultRole;
        }
    }

    public record Lending(
            int maxActiveLoans,
            int loanDays,
            String policyVersion,
            Duration attemptLease,
            Duration recoveryInterval,
            Duration expiryInterval,
            int expiryBatch,
            boolean redisEnabled,
            boolean dbOnlyOnRedisFailure
    ) {
        public Lending {
            maxActiveLoans = maxActiveLoans <= 0 ? 5 : maxActiveLoans;
            loanDays = loanDays <= 0 ? 14 : loanDays;
            policyVersion = policyVersion == null ? "v1" : policyVersion;
            attemptLease = attemptLease == null ? Duration.ofSeconds(30) : attemptLease;
            recoveryInterval = recoveryInterval == null ? Duration.ofSeconds(10) : recoveryInterval;
            expiryInterval = expiryInterval == null ? Duration.ofSeconds(30) : expiryInterval;
            expiryBatch = expiryBatch <= 0 ? 500 : expiryBatch;
        }
    }

    public record Outbox(
            Duration interval,
            int batchSize,
            Duration lease,
            boolean rocketmqEnabled,
            String endpoints
    ) {
        public Outbox {
            interval = interval == null ? Duration.ofMillis(200) : interval;
            batchSize = batchSize <= 0 ? 200 : batchSize;
            lease = lease == null ? Duration.ofSeconds(30) : lease;
        }
    }

    public record Cursor(String secret, Duration ttl) {
        public Cursor {
            secret = secret == null ? "change-me-in-production-32-bytes-min" : secret;
            ttl = ttl == null ? Duration.ofSeconds(60) : ttl;
        }
    }
}
