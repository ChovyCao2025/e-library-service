package com.elibrary.lending.infrastructure;

import com.elibrary.lending.application.ProjectionRebuildService;
import com.elibrary.shared.kernel.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 認領 DIRTY 或租約逾期的資源，將 MySQL 快照重建至新 Redis 代次。
 * 資料庫只包覆認領與完成 CAS，快照網路寫入位於交易之外。
 */
@Component
public class ProjectionRebuildWorker {
    private static final Logger log = LoggerFactory.getLogger(ProjectionRebuildWorker.class);
    private final ProjectionRebuildService service;
    private final RedisProjectionStore store;
    private final AppProperties properties;

    public ProjectionRebuildWorker(ProjectionRebuildService service, RedisProjectionStore store,
                                   AppProperties properties) {
        this.service = service;
        this.store = store;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.lending.recovery-interval:10s}")
    public void rebuildDirtyResources() {
        if (!workerEnabled() || !properties.lending().redisEnabled() || !store.available()) return;
        for (var candidate : service.candidates(20)) {
            String owner = UUID.randomUUID().toString();
            var control = service.claim(candidate, owner, Duration.ofSeconds(30));
            if (control == null) continue;
            try {
                // 認領提交後才讀取快照，新的業務寫入會受到 REBUILDING 狀態限制。
                if ("USER".equals(control.resourceType())) {
                    var snapshot = service.userSnapshot(control.resourceId());
                    store.rebuildUser(control.resourceId(), control.generation(), snapshot.count(),
                            snapshot.version(), snapshot.loans());
                } else {
                    var snapshot = service.bookSnapshot(control.resourceId());
                    store.rebuildBook(control.resourceId(), control.generation(), snapshot.available(),
                            snapshot.total(), snapshot.version(), snapshot.loans());
                }
                if (!service.complete(control, owner)) {
                    // owner 或 generation 已改變時，延遲 Worker 不得發布完成狀態。
                    log.warn("Projection rebuild owner changed for {}:{}", control.resourceType(), control.resourceId());
                }
            } catch (RuntimeException exception) {
                log.warn("Projection rebuild failed for {}:{}: {}", control.resourceType(), control.resourceId(),
                        exception.getMessage());
            }
        }
    }

    private boolean workerEnabled() {
        return "all".equalsIgnoreCase(properties.role()) || "worker".equalsIgnoreCase(properties.role());
    }
}
