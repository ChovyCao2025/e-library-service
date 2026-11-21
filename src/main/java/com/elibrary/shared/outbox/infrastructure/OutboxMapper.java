package com.elibrary.shared.outbox.infrastructure;

import com.elibrary.shared.outbox.domain.OutboxStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 交易式 Outbox 持久层契约。SQL 定义于 {@code mybatis/outbox/OutboxMapper.xml}；
 * owner 条件用于隔离直发执行绪与补偿执行器的租约所有权。
 */
@Mapper
public interface OutboxMapper {
    // 由聚合写入交易呼叫。
    int insert(@Param("id") long id, @Param("aggregateType") String aggregateType,
               @Param("aggregateId") long aggregateId, @Param("eventType") String eventType,
               @Param("aggregateVersion") long aggregateVersion, @Param("payload") String payload,
               @Param("status") OutboxStatus status,
               @Param("now") LocalDateTime now);

    // 直发与补偿流程共用的认领及确认生命周期。
    List<OutboxEventRow> lockReady(@Param("readyStatus") OutboxStatus readyStatus,
                                   @Param("claimedStatus") OutboxStatus claimedStatus,
                                   @Param("now") LocalDateTime now, @Param("limit") int limit);

    int claim(@Param("id") long id, @Param("owner") String owner,
              @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("targetStatus") OutboxStatus targetStatus);

    int claimReady(@Param("id") long id, @Param("owner") String owner,
                   @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil,
                   @Param("readyStatus") OutboxStatus readyStatus,
                   @Param("claimedStatus") OutboxStatus claimedStatus,
                   @Param("targetStatus") OutboxStatus targetStatus);

    List<OutboxEventRow> findClaimed(@Param("owner") String owner,
                                     @Param("status") OutboxStatus status);

    OutboxEventRow findClaimedById(@Param("id") long id, @Param("owner") String owner,
                                   @Param("status") OutboxStatus status);

    int markSent(@Param("id") long id, @Param("owner") String owner,
                 @Param("expectedStatus") OutboxStatus expectedStatus,
                 @Param("targetStatus") OutboxStatus targetStatus,
                 @Param("now") LocalDateTime now);

    int reschedule(@Param("id") long id, @Param("owner") String owner,
                   @Param("expectedStatus") OutboxStatus expectedStatus,
                   @Param("targetStatus") OutboxStatus targetStatus,
                   @Param("nextAttempt") LocalDateTime nextAttempt, @Param("error") String error);
}
