package com.elibrary.shared.kernel;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class SnowflakeIdGeneratorTest {
    @Test
    void generatesPositiveUniqueIds() {
        var generator = new SnowflakeIdGenerator(new AppProperties("all", null, null, null, null, 7));
        var values = new HashSet<Long>();
        for (int i = 0; i < 10_000; i++) {
            long id = generator.nextId();
            assertThat(id).isPositive();
            values.add(id);
        }
        assertThat(values).hasSize(10_000);
    }
}
