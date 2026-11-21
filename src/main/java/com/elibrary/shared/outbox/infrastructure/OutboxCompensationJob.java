package com.elibrary.shared.outbox.infrastructure;

import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.outbox.application.OutboxDeliveryService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 由 XXL-JOB 触发的 Outbox 兜底投递入口。
 * 本任务只负责定时触发，事件认领、租约校验和结果回写仍复用统一投递服务。
 */
@Component
@RequiredArgsConstructor
public class OutboxCompensationJob {
    public static final String HANDLER_NAME = "outboxCompensationJob";

    private final OutboxDeliveryService deliveryService;
    private final AppProperties properties;

    /**
     * 每次调度认领一个受控批次。到达重试时间的 NEW 事件和租约过期的 CLAIMED 事件
     * 都可以被接管，因此能够覆盖本地队列拒绝任务、MQ 发送失败、应用宕机以及
     * 发送成功但尚未回写 SENT 等情况。
     */
    @XxlJob(HANDLER_NAME)
    public void compensate() {
        if (!workerEnabled()) {
            XxlJobHelper.log("当前 APP_ROLE 跳过 Outbox 补偿");
            return;
        }
        int claimed = deliveryService.deliverReadyBatch();
        XxlJobHelper.log("Outbox 补偿本次认领 " + claimed + " 条事件");
    }

    private boolean workerEnabled() {
        return properties.getRole().isWorkerEnabled();
    }
}
