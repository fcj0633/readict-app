package com.readict.reading.application;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProgressCalculatorTest {
    private static final List<Integer> BLOCKS = List.of(3, 2, 3);

    @Test void calculatesBlockOffsetsInUtf16() {
        assertThat(ProgressCalculator.ratio(20, 8, BLOCKS, 0, 0, 100)).isEqualTo(new BigDecimal("0.1200000"));
        assertThat(ProgressCalculator.ratio(20, 8, BLOCKS, 1, 1, 100)).isEqualTo(new BigDecimal("0.1600000"));
        assertThat(ProgressCalculator.ratio(20, 8, BLOCKS, 2, 3, 100)).isEqualTo(new BigDecimal("0.2000000"));
    }

    @Test void fallsBackToChapterBoundaryWithoutBlock() {
        assertThat(ProgressCalculator.ratio(20, 8, BLOCKS, -1, null, 100)).isEqualTo(new BigDecimal("0.1200000"));
    }

    @Test void clampsOversizedOffsetAndRoundsToSevenDecimals() {
        assertThat(ProgressCalculator.ratio(20, 8, BLOCKS, 2, 99, 100)).isEqualTo(new BigDecimal("0.2000000"));
        assertThat(ProgressCalculator.ratio(2, 1, List.of(1), 0, 0, 3)).isEqualTo(new BigDecimal("0.3333333"));
    }

    @Test void rejectsInvalidArguments() {
        assertThatThrownBy(() -> ProgressCalculator.ratio(20, 8, BLOCKS, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProgressCalculator.ratio(20, 8, BLOCKS, 9, 0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProgressCalculator.ratio(4, 8, BLOCKS, 0, 0, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
