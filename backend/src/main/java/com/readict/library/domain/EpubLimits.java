package com.readict.library.domain;

/**
 * Server-side import budgets. Every limit is an engineering budget from the M03 design,
 * injected as one object so tests can shrink it and prove real truncation.
 */
public record EpubLimits(
        long maxSourceBytes,
        int maxEntries,
        long maxEntryBytes,
        long maxTotalBytes,
        int maxPathBytes,
        int maxDepth,
        int maxSpineItems,
        long maxChapterBytes,
        long maxArtifactBytes,
        long maxIndexBytes,
        long maxCoverBytes,
        int maxCoverPixels,
        int maxBlockUtf16,
        int maxImportSeconds) {

    public static EpubLimits defaults() {
        return new EpubLimits(25L * 1024 * 1024, 2500, 16L * 1024 * 1024, 80L * 1024 * 1024,
                512, 16, 1000, 8L * 1024 * 1024, 96L * 1024 * 1024, 8L * 1024 * 1024,
                4L * 1024 * 1024, 4096, 65536, 60);
    }

    public EpubLimits {
        if (maxSourceBytes <= 0 || maxEntries <= 0 || maxEntryBytes <= 0 || maxTotalBytes <= 0
                || maxPathBytes <= 0 || maxDepth <= 0 || maxSpineItems <= 0 || maxChapterBytes <= 0
                || maxArtifactBytes <= 0 || maxIndexBytes <= 0 || maxCoverBytes <= 0
                || maxCoverPixels <= 0 || maxBlockUtf16 <= 0 || maxImportSeconds <= 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }
}
