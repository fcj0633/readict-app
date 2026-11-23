package com.readict.reading.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Pure progress ratio maths shared by the service and unit tests. */
public final class ProgressCalculator {
    private ProgressCalculator() {}

    public static BigDecimal ratio(long chapterCumulative, long chapterTextLength,
                                   List<Integer> blockLengths, int blockIndex, Integer blockOffset,
                                   long totalUtf16) {
        if (totalUtf16 <= 0) throw new IllegalArgumentException("totalUtf16 must be positive");
        long chapterOffset;
        if (blockIndex < 0 || blockOffset == null) {
            chapterOffset = 0;
        } else {
            if (blockIndex >= blockLengths.size()) throw new IllegalArgumentException("block index out of range");
            long preceding = 0;
            for (int i = 0; i < blockIndex; i++) preceding += blockLengths.get(i);
            chapterOffset = preceding + blockOffset;
            long chapterLength = chapterCumulative - chapterTextLength;
            if (chapterOffset > chapterTextLength) chapterOffset = chapterTextLength;
            if (chapterLength < 0) throw new IllegalArgumentException("invalid chapter lengths");
        }
        long chapterStart = chapterCumulative - chapterTextLength;
        if (chapterStart < 0) throw new IllegalArgumentException("invalid chapter lengths");
        BigDecimal value = BigDecimal.valueOf(chapterStart + chapterOffset)
                .divide(BigDecimal.valueOf(totalUtf16), 7, RoundingMode.HALF_UP);
        if (value.signum() < 0) return BigDecimal.ZERO.setScale(7, RoundingMode.HALF_UP);
        if (value.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE.setScale(7, RoundingMode.HALF_UP);
        return value;
    }
}
