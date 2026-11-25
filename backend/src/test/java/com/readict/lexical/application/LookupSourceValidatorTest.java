package com.readict.lexical.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.readict.lexical.api.DictionaryLookupDtos.*;
import com.readict.lexical.core.DictionaryJson;
import com.readict.library.infrastructure.persistence.entity.BookArtifactEntity;
import com.readict.reading.application.ReadingIndexCache;
import com.readict.shared.web.ApiProblem;
import tools.jackson.databind.JsonNode;

class LookupSourceValidatorTest {
    private static final String TEXT = "The children are running.";
    private final ReadingIndexCache indexes = mock(ReadingIndexCache.class);
    private final LookupSourceValidator validator = new LookupSourceValidator(indexes);
    private final BookArtifactEntity artifact = artifact();

    @Test void rebuildsCanonicalSourceFromTheImmutableIndex() {
        when(indexes.chapter(eq(artifact), eq("OEBPS/Text/ch1.xhtml"))).thenReturn(index(TEXT, 0, TEXT.length()));
        SourceView result = validator.validate(artifact, source(TEXT, 4, 12));
        assertThat(result.surface()).isEqualTo("children");
        assertThat(result.sentence()).isEqualTo(TEXT);
        assertThat(result.wordStart()).isEqualTo(4);
        assertThat(result.wordEnd()).isEqualTo(12);
        assertThat(result.contextKind()).isEqualTo("sentence");
        assertThat(result.quote().exact()).isEqualTo(TEXT);
        assertThat(result.artifactHash()).isEqualTo("1515");
    }

    @Test void rejectsForgedSurfaceAndOutOfRangeOffsets() {
        when(indexes.chapter(any(), any())).thenReturn(index(TEXT, 0, TEXT.length()));
        assertThat(code(() -> validator.validate(artifact, new SourceContext(artifact.getId(), "OEBPS/Text/ch1.xhtml",
                "b-1", 0, TEXT.length(), TEXT, "children", 4, 11,
                "epubcfi(/6/2!/4/2/1:4)", "epubcfi(/6/2!/4/2/1:0)",
                new Quote(TEXT, "", ""))))).isEqualTo("SOURCE_INVALID");
        assertThat(code(() -> validator.validate(artifact, source(TEXT, 4, 99)))).isEqualTo("SOURCE_INVALID");
        assertThat(code(() -> validator.validate(artifact, new SourceContext(artifact.getId(), "OEBPS/Text/ch1.xhtml",
                "b-1", 0, TEXT.length(), "forged", "children", 4, 12,
                "epubcfi(/6/2!/4/2/1:4)", "epubcfi(/6/2!/4/2/1:0)",
                new Quote("forged", "", ""))))).isEqualTo("SOURCE_INVALID");
    }

    @Test void rejectsSurrogateSplits() {
        String text = "a😀b";
        when(indexes.chapter(any(), any())).thenReturn(index(text, 0, text.length()));
        SourceContext split = new SourceContext(artifact.getId(), "OEBPS/Text/ch1.xhtml", "b-1",
                0, text.length(), text, "😀", 1, 3,
                "epubcfi(/6/2!/4/2/1:1)", "epubcfi(/6/2!/4/2/1:0)",
                new Quote(text, "", ""));
        assertThatCode(() -> validator.validate(artifact, split)).doesNotThrowAnyException();
        SourceContext middle = new SourceContext(artifact.getId(), "OEBPS/Text/ch1.xhtml", "b-1",
                0, text.length(), text, "😀", 2, 3,
                "epubcfi(/6/2!/4/2/1:2)", "epubcfi(/6/2!/4/2/1:0)",
                new Quote(text, "", ""));
        assertThat(code(() -> validator.validate(artifact, middle))).isEqualTo("SOURCE_INVALID");
    }

    @Test void acceptsSentenceRelativeOffsetsInLaterSentencesAndRejectsBlockRelativeOffsets() {
        String prefix = "First book. ";
        String sentence = TEXT;
        String suffix = " Last word";
        String text = prefix + sentence + suffix;
        int start = prefix.length();
        int end = start + sentence.length();
        when(indexes.chapter(any(), any())).thenReturn(index(text, start, end));
        Quote quote = new Quote(sentence, prefix, suffix);
        SourceContext correct = new SourceContext(artifact.getId(), "OEBPS/Text/ch1.xhtml", "b-1",
                start, end, sentence, "children", 4, 12,
                "epubcfi(/6/2!/4/2/1:16)", "epubcfi(/6/2!/4/2/1:12)", quote);
        SourceView result = validator.validate(artifact, correct);
        assertThat(result.surface()).isEqualTo("children");
        assertThat(result.wordStart()).isEqualTo(4);
        assertThat(result.sentenceStart()).isEqualTo(start);
        SourceContext oldClient = new SourceContext(artifact.getId(), "OEBPS/Text/ch1.xhtml", "b-1",
                start, end, sentence, "children", start + 4, start + 12,
                correct.wordRangeCfi(), correct.sentenceRangeCfi(), quote);
        assertThat(code(() -> validator.validate(artifact, oldClient))).isEqualTo("SOURCE_INVALID");
    }

    private static String code(Runnable action) {
        try { action.run(); return "NO_FAILURE"; }
        catch (ApiProblem problem) { return problem.code(); }
    }

    private static BookArtifactEntity artifact() {
        BookArtifactEntity value = new BookArtifactEntity();
        value.setId(UUID.fromString("a0000000-0000-4000-8000-000000000001"));
        value.setArtifactHash(new byte[]{0x15, 0x15});
        value.setPipelineVersion("epub-safe-v1");
        return value;
    }

    private static JsonNode index(String text, int start, int end) {
        String hash = DictionaryJson.digest(text.getBytes(StandardCharsets.UTF_8));
        return DictionaryJson.parse("{\"blocks\":[{\"blockId\":\"b-1\",\"text\":\"" + text
                + "\",\"utf16Length\":" + text.length() + ",\"textHash\":\"" + hash
                + "\",\"sentences\":[{\"start\":" + start + ",\"end\":" + end + ",\"kind\":\"SENTENCE\"}]}]}");
    }

    private SourceContext source(String sentence, int wordStart, int wordEnd) {
        String surface = sentence.substring(wordStart, Math.min(wordEnd, sentence.length()));
        return new SourceContext(artifact.getId(), "OEBPS/Text/ch1.xhtml", "b-1", 0, sentence.length(),
                sentence, surface, wordStart, wordEnd,
                "epubcfi(/6/2!/4/2/1:" + wordStart + ")", "epubcfi(/6/2!/4/2/1:0)",
                new Quote(sentence, "", ""));
    }
}
