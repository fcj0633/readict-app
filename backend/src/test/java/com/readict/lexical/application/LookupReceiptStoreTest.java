package com.readict.lexical.application;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.readict.lexical.api.DictionaryLookupDtos.*;
import com.readict.shared.web.ApiProblem;

class LookupReceiptStoreTest {
    private static final SourceView SOURCE = new SourceView(
            UUID.fromString("a0000000-0000-4000-8000-000000000001"), "a".repeat(64), "epub-safe-v1",
            "OEBPS/Text/ch1.xhtml", "b-1", 0, 25, "sentence", "The children are running.", "children",
            4, 12, "epubcfi(/6/2!/4/2/1:4)", "epubcfi(/6/2!/4/2/1:0)",
            new Quote("The children are running.", "", ""));

    @Test void createsAndReadsReceiptsForTheOwnerOnly() {
        var store = new LookupReceiptStore(Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC));
        UUID owner = UUID.randomUUID();
        var receipt = store.create(owner, UUID.randomUUID(), SOURCE, "FOUND", null,
                List.of("e1"), List.of("s1"));
        assertThat(store.find(receipt.id(), owner)).isSameAs(receipt);
        assertThatThrownBy(() -> store.find(receipt.id(), UUID.randomUUID()))
                .isInstanceOf(ApiProblem.class)
                .satisfies(error -> assertThat(((ApiProblem) error).code()).isEqualTo("LOOKUP_NOT_FOUND"));
    }

    @Test void rejectsOversizedReceipts() {
        var store = new LookupReceiptStore(Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC));
        String huge = "x".repeat(200_000);
        SourceView oversized = new SourceView(SOURCE.artifactId(), SOURCE.artifactHash(), SOURCE.pipelineVersion(),
                SOURCE.spineHref(), SOURCE.blockId(), 0, 200_000, "sentence", huge, "x",
                0, 1, SOURCE.wordRangeCfi(), SOURCE.sentenceRangeCfi(), new Quote(huge, "", ""));
        assertThatThrownBy(() -> store.create(UUID.randomUUID(), UUID.randomUUID(), oversized, "FOUND", null,
                List.of("e1"), List.of()))
                .isInstanceOf(ApiProblem.class)
                .satisfies(error -> assertThat(((ApiProblem) error).code()).isEqualTo("LOOKUP_CAPACITY_EXCEEDED"));
    }
}
