package com.readict.reading.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReadingProgressView(UUID bookId, AnchorView anchor, BigDecimal progressRatio,
                                  long version, Instant updatedAt) {
    public record AnchorView(String artifactId, String artifactHash, String pipelineVersion,
                             String spineHref, String cfi, String blockId, Integer blockOffset,
                             QuoteView quote) {}

    public record QuoteView(String exact, String prefix, String suffix) {}
}
