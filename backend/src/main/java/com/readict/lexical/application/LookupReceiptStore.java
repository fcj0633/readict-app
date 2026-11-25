package com.readict.lexical.application;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import com.readict.lexical.api.DictionaryLookupDtos.*;
import com.readict.shared.web.ApiProblem;

/**
 * Process-local short-lived lookup receipts. The MVP does not persist snapshots; M06 must
 * revalidate the account, book, artifact and rights before using a receipt.
 */
@Component
public class LookupReceiptStore {
    private static final int MAX_ITEMS = 1000;
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final long MAX_ITEM_BYTES = 256L * 1024;
    private final Clock clock;
    private final LinkedHashMap<UUID, Receipt> items = new LinkedHashMap<>(64, .75f, true);
    private long bytes;

    public record Receipt(UUID id, UUID userId, UUID bookId, UUID artifactId, String status,
                          DatasetView dataset, SourceView source, List<String> entryIds,
                          List<String> senseIds, Instant expiresAt) {}

    public LookupReceiptStore(Clock clock) { this.clock = clock; }

    public synchronized Receipt create(UUID userId, UUID bookId, SourceView source, String status,
                                       DatasetView dataset, List<String> entryIds, List<String> senseIds) {
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(600);
        UUID id = UUID.randomUUID();
        Receipt receipt = new Receipt(id, userId, bookId, source.artifactId(), status, dataset, source,
                List.copyOf(entryIds), List.copyOf(senseIds), expiresAt);
        long size = estimate(receipt);
        if (size > MAX_ITEM_BYTES) throw new ApiProblem(503, "LOOKUP_CAPACITY_EXCEEDED", "查询结果过大，请稍后重试");
        evict(now);
        while (!items.isEmpty() && (items.size() >= MAX_ITEMS || bytes + size > MAX_BYTES)) {
            evictOldest();
        }
        items.put(id, receipt);
        bytes += size;
        return receipt;
    }

    /** M06 can use this after checking the owner; no public HTTP endpoint is exposed. */
    public synchronized Receipt find(UUID id, UUID userId) {
        Receipt receipt = items.get(id);
        if (receipt == null) throw new ApiProblem(410, "LOOKUP_EXPIRED", "查询凭据已过期");
        if (!receipt.userId().equals(userId)) throw new ApiProblem(404, "LOOKUP_NOT_FOUND", "查询凭据不存在");
        if (clock.instant().isAfter(receipt.expiresAt())) {
            items.remove(id);
            bytes -= estimate(receipt);
            throw new ApiProblem(410, "LOOKUP_EXPIRED", "查询凭据已过期");
        }
        return receipt;
    }

    private void evict(Instant now) {
        var iterator = items.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (now.isAfter(entry.getValue().expiresAt())) {
                bytes -= estimate(entry.getValue());
                iterator.remove();
            }
        }
    }

    private void evictOldest() {
        var iterator = items.entrySet().iterator();
        if (iterator.hasNext()) {
            var entry = iterator.next();
            bytes -= estimate(entry.getValue());
            iterator.remove();
        }
    }

    private static long estimate(Receipt receipt) {
        long size = 256;
        if (receipt.source() != null) {
            size += length(receipt.source().sentence()) * 2L + length(receipt.source().surface()) * 2L;
            size += length(receipt.source().spineHref()) * 2L + length(receipt.source().blockId()) * 2L;
            if (receipt.source().quote() != null) {
                size += length(receipt.source().quote().exact()) * 2L;
                size += length(receipt.source().quote().prefix()) * 2L;
                size += length(receipt.source().quote().suffix()) * 2L;
            }
        }
        size += receipt.entryIds().size() * 80L + receipt.senseIds().size() * 80L;
        return size;
    }

    private static int length(String value) { return value == null ? 0 : value.length(); }

    synchronized int size() { return items.size(); }
    synchronized long bytes() { return bytes; }
}
