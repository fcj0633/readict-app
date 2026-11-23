package com.readict.library.domain;

/** Import executor and lease tuning, externalised through {@code readict.library.import.*}. */
public record ImportSettings(
        int concurrency,
        int queueCapacity,
        int perUserLimit,
        int leaseSeconds,
        int renewSeconds,
        int maxAttempts,
        int timeoutSeconds) {

    public ImportSettings {
        if (concurrency < 1 || concurrency > 4) throw new IllegalArgumentException("concurrency out of range");
        if (queueCapacity < 1 || perUserLimit < 1 || leaseSeconds < 10 || renewSeconds < 1
                || maxAttempts < 1 || timeoutSeconds < 5) {
            throw new IllegalArgumentException("import settings out of range");
        }
        if (renewSeconds * 2 >= leaseSeconds) throw new IllegalArgumentException("lease must outlive two renewals");
    }
}
