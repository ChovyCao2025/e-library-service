package com.elibrary;

import com.elibrary.lending.application.LendingService;
import com.elibrary.shared.web.DomainException;
import com.elibrary.shared.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "app.role=api",
        "app.lending.redis-enabled=false",
        "app.lending.db-only-on-redis-failure=true"
})
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

        assertThat(created.replay()).isFalse();
        assertThat(replay.replay()).isTrue();
        assertThat(replay.loan().loanId()).isEqualTo(created.loan().loanId());
        assertThat(count("SELECT active_count FROM user_lending_account WHERE user_id=10001")).isEqualTo(1);
        assertThat(count("SELECT available_copies FROM lending_stock WHERE book_id=2000001")).isZero();

        assertThatThrownBy(() -> lending.borrow(10_002L, bookId, "integration-002"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.BOOK_SOLD_OUT));
        assertThatThrownBy(() -> lending.borrow(10_001L, 1_000_001L, "integration-001"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));

        long loanId = Long.parseUnsignedLong(created.loan().loanId());
        var returned = lending.returnLoan(10_001L, loanId, "MANUAL");
        var returnedAgain = lending.returnLoan(10_001L, loanId, "MANUAL");
        assertThat(returnedAgain.returnedAt()).isEqualTo(returned.returnedAt());
        assertThat(count("SELECT active_count FROM user_lending_account WHERE user_id=10001")).isZero();
        assertThat(count("SELECT available_copies FROM lending_stock WHERE book_id=2000001")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM loan WHERE user_id=10001 AND book_id=2000001")).isEqualTo(1);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
