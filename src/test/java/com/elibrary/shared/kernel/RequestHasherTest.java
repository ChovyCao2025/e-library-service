package com.elibrary.shared.kernel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHasherTest {
    private final RequestHasher hasher = new RequestHasher();

    @Test
    void borrowFingerprintIsStableAndParameterSensitive() {
        assertThat(hasher.borrow(42)).isEqualTo(hasher.borrow(42));
        assertThat(hasher.borrow(42)).hasSize(64).isNotEqualTo(hasher.borrow(43));
    }
}
