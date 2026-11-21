package com.elibrary.shared.kernel;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证同一节点连续产生的识别码皆为正数且不重复。 */
class SnowflakeIdGeneratorTest {
    @Test
    void generatesPositiveUniqueIds() {
        var generator = new SnowflakeIdGenerator(
                new AppProperties(ApplicationRole.ALL, null, null, null, 7));
        var values = new HashSet<Long>();
        for (int i = 0; i < 10_000; i++) {
            long id = generator.nextId();
            assertThat(id).isPositive();
            values.add(id);
        }
        assertThat(values).hasSize(10_000);
    }
}
