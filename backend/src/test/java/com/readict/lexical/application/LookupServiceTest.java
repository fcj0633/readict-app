package com.readict.lexical.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.readict.identity.domain.ReadictPrincipal;
import com.readict.lexical.api.DictionaryLookupDtos.*;
import com.readict.lexical.audio.AudioCatalog;
import com.readict.lexical.core.DictionaryJson;
import com.readict.lexical.core.DictionaryProvider;
import com.readict.library.infrastructure.persistence.entity.BookArtifactEntity;
import com.readict.library.infrastructure.persistence.entity.BookEntity;
import com.readict.library.infrastructure.persistence.mapper.BookArtifactMapper;
import com.readict.library.infrastructure.persistence.mapper.BookMapper;
import tools.jackson.databind.JsonNode;

class LookupServiceTest {
    private final BookMapper books = mock(BookMapper.class);
    private final BookArtifactMapper artifacts = mock(BookArtifactMapper.class);
    private final LookupSourceValidator sources = mock(LookupSourceValidator.class);
    private final DictionaryProvider dictionary = mock(DictionaryProvider.class);
    private final AudioCatalog audio = mock(AudioCatalog.class);
    private final LookupReceiptStore receipts = mock(LookupReceiptStore.class);
    private final LookupService service = new LookupService(books, artifacts, sources, dictionary, audio, receipts);

    @Test void assemblesRegionsAndReusesOneAudioSnapshot() {
        UUID userId = UUID.randomUUID(), bookId = UUID.randomUUID(), artifactId = UUID.randomUUID();
        BookEntity book = new BookEntity(); book.setId(bookId); book.setStatus("READY"); book.setActiveArtifactId(artifactId);
        BookArtifactEntity artifact = new BookArtifactEntity(); artifact.setId(artifactId); artifact.setStatus("READY");
        when(books.findOwned(bookId, userId)).thenReturn(book);
        when(artifacts.selectById(artifactId)).thenReturn(artifact);
        SourceView source = source(artifactId);
        when(sources.validate(eq(artifact), any())).thenReturn(source);
        JsonNode regions = DictionaryJson.parse("[{\"entryId\":\"e1\",\"headword\":\"child\",\"senses\":[{\"senseId\":\"s1\"}]},"
                + "{\"entryId\":\"e2\",\"headword\":\"good\",\"senses\":[{\"senseId\":\"s2\"}]}]");
        DictionaryProvider.DatasetRef dataset = new DictionaryProvider.DatasetRef("dict-1", "v1", "col-normalize-v1", "local-col", "unverified");
        when(dictionary.lookup("children", null)).thenReturn(new DictionaryProvider.LookupResult("AMBIGUOUS", "children", dataset, null, regions));
        var item = new AudioCatalog.Item("audio-1", "en-GB", "audio/mpeg", 100);
        when(audio.find("child", "col-normalize-v1")).thenReturn(new AudioCatalog.Pronunciations("available", "child", "local-sound", "audio-1", "audio-v1", List.of(item)));
        when(audio.findInDataset("good", "col-normalize-v1", "audio-1")).thenReturn(new AudioCatalog.Pronunciations("missing", "good", "local-sound", "audio-1", "audio-v1", List.of()));
        UUID receiptId = UUID.randomUUID();
        when(receipts.create(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(new LookupReceiptStore.Receipt(
                receiptId, userId, bookId, artifactId, "AMBIGUOUS", null, source, List.of("e1"), List.of("s1"), Instant.now().plusSeconds(600), List.of()));

        LookupResponse response = service.lookup(new ReadictPrincipal(userId, 0, Instant.now()),
                new LookupRequest(bookId, new SourceContext(artifactId, "OEBPS/Text/ch1.xhtml", "b-1", 0, 25, "The children are running.", "children", 4, 12, "epubcfi(/6/2!/4/2/1:4)", "epubcfi(/6/2!/4/2/1:0)", new Quote("The children are running.", "", "")), null, null));

        assertThat(response.lookupId()).isEqualTo(receiptId);
        assertThat(response.status()).isEqualTo("AMBIGUOUS");
        assertThat(response.entry()).isNull();
        assertThat(response.regions()).hasSize(2);
        assertThat(response.regions().get(0).path("pronunciations").path("status").asString()).isEqualTo("available");
        assertThat(response.regions().get(1).path("pronunciations").path("status").asString()).isEqualTo("missing");
        assertThat(response.audioError()).isNull();
        verify(audio, times(1)).find("child", "col-normalize-v1");
        verify(audio, times(1)).findInDataset("good", "col-normalize-v1", "audio-1");
    }

    @Test void keepsTextSuccessWhenAudioDependencyFails() {
        UUID userId = UUID.randomUUID(), bookId = UUID.randomUUID(), artifactId = UUID.randomUUID();
        BookEntity book = new BookEntity(); book.setId(bookId); book.setStatus("READY"); book.setActiveArtifactId(artifactId);
        BookArtifactEntity artifact = new BookArtifactEntity(); artifact.setId(artifactId); artifact.setStatus("READY");
        when(books.findOwned(bookId, userId)).thenReturn(book);
        when(artifacts.selectById(artifactId)).thenReturn(artifact);
        SourceView source = source(artifactId);
        when(sources.validate(eq(artifact), any())).thenReturn(source);
        DictionaryProvider.DatasetRef dataset = new DictionaryProvider.DatasetRef("dict-1", "v1", "col-normalize-v1", "local-col", "unverified");
        when(dictionary.lookup("children", null)).thenReturn(new DictionaryProvider.LookupResult("FOUND", "children", dataset,
                "child", DictionaryJson.parse("[{\"entryId\":\"e1\",\"headword\":\"child\",\"senses\":[]}]")));
        when(audio.find("child", "col-normalize-v1")).thenThrow(new com.readict.lexical.core.DictionaryJson.DictionaryFailure("AUDIO_NOT_READY"));
        when(receipts.create(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(new LookupReceiptStore.Receipt(
                UUID.randomUUID(), userId, bookId, artifactId, "FOUND", null, source, List.of("e1"), List.of(), Instant.now(), List.of()));

        LookupResponse response = service.lookup(new ReadictPrincipal(userId, 0, Instant.now()),
                new LookupRequest(bookId, new SourceContext(artifactId, "OEBPS/Text/ch1.xhtml", "b-1", 0, 25, "The children are running.", "children", 4, 12, "epubcfi(/6/2!/4/2/1:4)", "epubcfi(/6/2!/4/2/1:0)", new Quote("The children are running.", "", "")), null, null));
        assertThat(response.status()).isEqualTo("FOUND");
        assertThat(response.audioError()).isEqualTo("AUDIO_NOT_READY");
        assertThat(response.entry()).isNotNull();
    }

    private static SourceView source(UUID artifactId) {
        return new SourceView(artifactId, "a".repeat(64), "epub-safe-v1", "OEBPS/Text/ch1.xhtml", "b-1",
                0, 25, "sentence", "The children are running.", "children", 4, 12,
                "epubcfi(/6/2!/4/2/1:4)", "epubcfi(/6/2!/4/2/1:0)",
                new Quote("The children are running.", "", ""));
    }
}
