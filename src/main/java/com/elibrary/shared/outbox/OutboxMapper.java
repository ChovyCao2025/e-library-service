package com.elibrary.shared.outbox;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 交易式 Outbox 持久層契約。SQL 定義於 {@code mybatis/outbox/OutboxMapper.xml}；
 * owner 條件用於隔離過期的 Relay Worker。
 */
@Mapper
public interface OutboxMapper {
    // 由聚合寫入交易呼叫。
    int insert(@Param("id") long id, @Param("aggregateType") String aggregateType,
               @Param("aggregateId") long aggregateId, @Param("eventType") String eventType,
               @Param("aggregateVersion") long aggregateVersion, @Param("payload") String payload,
               @Param("now") LocalDateTime now);

    int insertSearchDeliveries(@Param("eventId") long eventId, @Param("now") LocalDateTime now);

    // Relay 認領與確認生命週期。
    List<OutboxEventRow> lockReady(@Param("now") LocalDateTime now, @Param("limit") int limit);

    int claim(@Param("id") long id, @Param("owner") String owner,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    List<OutboxEventRow> findClaimed(@Param("owner") String owner);

    int markSent(@Param("id") long id, @Param("owner") String owner, @Param("now") LocalDateTime now);

    int reschedule(@Param("id") long id, @Param("owner") String owner,
                   @Param("nextAttempt") LocalDateTime nextAttempt, @Param("error") String error);
}
