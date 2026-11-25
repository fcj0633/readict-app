package com.readict.lexical.application;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.readict.lexical.api.DictionaryLookupDtos.*;
import com.readict.lexical.core.DictionaryJson;
import com.readict.library.infrastructure.persistence.entity.BookArtifactEntity;
import com.readict.reading.application.ReadingIndexCache;
import com.readict.shared.web.ApiProblem;
import tools.jackson.databind.JsonNode;

/** Validates client-claimed source facts against the immutable M03 index. */
@Component
public class LookupSourceValidator {
    private static final int MAX_SURFACE = 128;
    private static final int MAX_SENTENCE = 10000;
    private static final int MAX_HREF = 512;
    private static final int MAX_BLOCK_ID = 128;
    private static final int MAX_QUOTE_AFFIX = 128;
    private static final int MAX_CFI_BYTES = 4096;

    private final ReadingIndexCache indexes;

    public LookupSourceValidator(ReadingIndexCache indexes) { this.indexes = indexes; }

    public SourceView validate(BookArtifactEntity artifact, SourceContext source) {
        if (source == null) invalid();
        if (source.artifactId() == null || !source.artifactId().equals(artifact.getId())) invalid();
        requireText(source.spineHref(), MAX_HREF);
        requireText(source.blockId(), MAX_BLOCK_ID);
        requireText(source.surface(), MAX_SURFACE);
        requireText(source.sentence(), MAX_SENTENCE);
        if (source.sentenceStart() == null || source.sentenceEnd() == null
                || source.wordStart() == null || source.wordEnd() == null) invalid();
        int sentenceStart = source.sentenceStart();
        int sentenceEnd = source.sentenceEnd();
        int wordStart = source.wordStart();
        int wordEnd = source.wordEnd();
        if (sentenceStart < 0 || sentenceEnd <= sentenceStart || wordStart < 0 || wordEnd <= wordStart) invalid();
        if (source.quote() == null) invalid();
        requireQuote(source.quote());

        JsonNode chapter;
        try {
            chapter = indexes.chapter(artifact, source.spineHref());
        } catch (ApiProblem problem) {
            throw new ApiProblem(422, "SOURCE_INVALID", "阅读位置不可用");
        }
        JsonNode block = null;
        for (JsonNode candidate : chapter.path("blocks")) {
            if (source.blockId().equals(candidate.path("blockId").asString())) { block = candidate; break; }
        }
        if (block == null) invalid();
        String text = block.path("text").asString();
        if (text.length() != block.path("utf16Length").asInt()
                || !DictionaryJson.digest(text.getBytes(StandardCharsets.UTF_8)).equals(block.path("textHash").asString())) {
            throw new ApiProblem(422, "TEXT_INDEX_MISMATCH", "正文索引已变化，请刷新后重试");
        }
        if (sentenceEnd > text.length() || wordStart > sentenceEnd - sentenceStart
                || wordEnd > sentenceEnd - sentenceStart) invalid();
        boundary(text, sentenceStart);
        boundary(text, sentenceEnd);
        boundary(text, sentenceStart + wordStart);
        boundary(text, sentenceStart + wordEnd);

        JsonNode sentenceNode = null;
        for (JsonNode candidate : block.path("sentences")) {
            if (candidate.path("start").asInt() == sentenceStart && candidate.path("end").asInt() == sentenceEnd) {
                sentenceNode = candidate;
                break;
            }
        }
        if (sentenceNode == null) invalid();
        String sentence = text.substring(sentenceStart, sentenceEnd);
        if (!sentence.equals(source.sentence())) invalid();
        String surface = sentence.substring(wordStart, wordEnd);
        if (!surface.equals(source.surface())) invalid();

        String prefix = text.substring(prefixStart(text, sentenceStart), sentenceStart);
        String suffix = text.substring(sentenceEnd, suffixEnd(text, sentenceEnd));
        if (!source.quote().exact().equals(sentence)
                || !prefix.equals(source.quote().prefix()) || !suffix.equals(source.quote().suffix())) invalid();
        requireCfi(source.wordRangeCfi());
        requireCfi(source.sentenceRangeCfi());
        String kind = "FRAGMENT".equals(sentenceNode.path("kind").asString()) ? "fragment" : "sentence";

        return new SourceView(artifact.getId(), hex(artifact.getArtifactHash()), artifact.getPipelineVersion(),
                source.spineHref(), source.blockId(), sentenceStart, sentenceEnd, kind, sentence, surface,
                wordStart, wordEnd, source.wordRangeCfi(), source.sentenceRangeCfi(),
                new Quote(sentence, prefix, suffix));
    }

    private static void requireQuote(Quote quote) {
        if (quote.exact() == null || quote.prefix() == null || quote.suffix() == null) invalid();
        if (quote.exact().length() > MAX_SENTENCE || quote.prefix().length() > MAX_QUOTE_AFFIX
                || quote.suffix().length() > MAX_QUOTE_AFFIX) invalid();
    }

    private static void requireText(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) invalid();
    }

    private static void requireCfi(String value) {
        if (value == null || !value.startsWith("epubcfi(")
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_CFI_BYTES) invalid();
    }

    private static int prefixStart(String text, int index) {
        int start = Math.max(0, index - MAX_QUOTE_AFFIX);
        if (start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))) start--;
        return start;
    }

    private static int suffixEnd(String text, int index) {
        int end = Math.min(text.length(), index + MAX_QUOTE_AFFIX);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end++;
        return end;
    }

    private static void boundary(String text, int index) {
        if (index < 0 || index > text.length()) invalid();
        if (index > 0 && index < text.length()
                && (Character.isLowSurrogate(text.charAt(index)) || Character.isHighSurrogate(text.charAt(index - 1)))) {
            invalid();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return value.toString();
    }

    private static void invalid() {
        throw new ApiProblem(422, "SOURCE_INVALID", "阅读位置不可用，请刷新后重试");
    }
}
