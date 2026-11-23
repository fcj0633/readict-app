package com.readict.reading.application;

import com.readict.shared.web.ApiProblem;

/** Transport-independent anchor checks; CFI is treated as an opaque bounded string. */
public final class ProgressValidation {
    public static final int MAX_CFI = 4096;
    public static final int MAX_HREF = 512;
    public static final int MAX_BLOCK_ID = 128;
    public static final int MAX_QUOTE = 256;
    public static final int MAX_AFFIX = 128;

    private ProgressValidation() {}

    public static void validateAnchor(String cfi, String spineHref, String blockId, Integer blockOffset,
                                      String quoteExact, String quotePrefix, String quoteSuffix) {
        if (cfi == null || cfi.isBlank() || cfi.length() > MAX_CFI) invalid();
        if (!cfi.startsWith("epubcfi(")) invalid();
        if (spineHref == null || spineHref.isBlank() || spineHref.length() > MAX_HREF) invalid();
        if ((blockId == null) != (blockOffset == null)) invalid();
        if (blockId != null && (blockId.isBlank() || blockId.length() > MAX_BLOCK_ID)) invalid();
        if (blockOffset != null && blockOffset < 0) invalid();
        boolean anyQuote = quoteExact != null || quotePrefix != null || quoteSuffix != null;
        if (anyQuote) {
            if (quoteExact == null || quotePrefix == null || quoteSuffix == null) invalid();
            if (quoteExact.isEmpty() || quoteExact.length() > MAX_QUOTE) invalid();
            if (quotePrefix.length() > MAX_AFFIX || quoteSuffix.length() > MAX_AFFIX) invalid();
        }
    }

    public static void validateRequest(Long expectedVersion, Object requestId) {
        if (expectedVersion == null || expectedVersion < 0 || requestId == null) {
            throw new ApiProblem(400, "INVALID_REQUEST", "进度请求格式有误");
        }
    }

    private static void invalid() {
        throw new ApiProblem(422, "ANCHOR_INVALID", "阅读位置不可用，请刷新后重试");
    }
}
