package com.elibrary;

import com.elibrary.lending.application.LendingService;
import com.elibrary.lending.domain.ReturnReason;
import com.elibrary.lending.interfaces.LendingDtos;
import com.elibrary.shared.web.api.ErrorCode;
import com.elibrary.shared.web.error.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 以真实 MySQL 验证借阅幂等、售罄、归还与权威计数不变量。 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "app.role=api")
class LendingMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7")
            .withDatabaseName("elibrary")
            .withUsername("elibrary")
            .withPassword("elibrary");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired LendingService lending;
    @Autowired JdbcTemplate jdbc;

    @Test
    void borrowReplaySoldOutAndIdempotentReturnPreserveInvariants() {
        long bookId = 2_000_001L;
        var created = lending.borrow(10_001L, bookId, "integration-001");
        var replay = lending.borrow(10_001L, bookId, "integration-001");

        assertThat(created.isReplay()).isFalse();
        assertThat(replay.isReplay()).isTrue();
        assertThat(replay.getLoan().getLoanId()).isEqualTo(created.getLoan().getLoanId());
        assertThat(count("SELECT active_count FROM user_lending_account WHERE user_id=10001")).isEqualTo(1);
        assertThat(count("SELECT available_copies FROM lending_stock WHERE book_id=2000001")).isZero();

        assertThatThrownBy(() -> lending.borrow(10_002L, bookId, "integration-002"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.BOOK_SOLD_OUT));
        assertThatThrownBy(() -> lending.borrow(10_001L, 1_000_001L, "integration-001"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));

        long loanId = Long.parseUnsignedLong(created.getLoan().getLoanId());
        var returned = lending.returnLoan(10_001L, loanId, ReturnReason.MANUAL);
        var returnedAgain = lending.returnLoan(10_001L, loanId, ReturnReason.MANUAL);
        assertThat(returnedAgain.getReturnedAt()).isEqualTo(returned.getReturnedAt());
        assertThat(count("SELECT active_count FROM user_lending_account WHERE user_id=10001")).isZero();
        assertThat(count("SELECT available_copies FROM lending_stock WHERE book_id=2000001")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM loan WHERE user_id=10001 AND book_id=2000001")).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKeyCreateOneLoan() throws Exception {
        long userId = 11_001L;
        long bookId = 1_000_001L;
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> borrowTogether(userId, bookId, ready, start));
            var second = executor.submit(() -> borrowTogether(userId, bookId, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(firstResult.getLoan().getLoanId()).isEqualTo(secondResult.getLoan().getLoanId());
            assertThat(firstResult.isReplay()).isNotEqualTo(secondResult.isReplay());
        }

        assertThat(count("SELECT COUNT(*) FROM borrow_request WHERE user_id=11001")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM loan WHERE user_id=11001 AND book_id=1000001")).isEqualTo(1);
        assertThat(count("SELECT active_count FROM user_lending_account WHERE user_id=11001")).isEqualTo(1);
        assertThat(count("SELECT available_copies FROM lending_stock WHERE book_id=1000001")).isEqualTo(4);
    }

    /** 让两个执行绪同时送出相同幂等命令，重现唯一键竞争路径。 */
    private LendingDtos.BorrowOutcome borrowTogether(
            long userId, long bookId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return lending.borrow(userId, bookId, "integration-concurrent-001");
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
