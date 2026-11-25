package com.readict.lexical.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** HTTP contract for the M05 lookup endpoint; nested records keep the wire types together. */
public final class DictionaryLookupDtos {
    private DictionaryLookupDtos() {}

    public record Quote(String exact, String prefix, String suffix) {}

    public record SourceContext(UUID artifactId, String spineHref, String blockId,
                                Integer sentenceStart, Integer sentenceEnd, String sentence,
                                String surface, Integer wordStart, Integer wordEnd,
                                String wordRangeCfi, String sentenceRangeCfi, Quote quote) {}

    public record LookupRequest(UUID bookId, SourceContext source, String posHint, String lemmaCandidate) {}

    public record DatasetView(String datasetId, String datasetVersion, String normalizationVersion,
                              String provider, String policyVersion) {}

    public record SourceView(UUID artifactId, String artifactHash, String pipelineVersion,
                             String spineHref, String blockId, int sentenceStart, int sentenceEnd,
                             String contextKind, String sentence, String surface,
                             int wordStart, int wordEnd, String wordRangeCfi, String sentenceRangeCfi,
                             Quote quote) {}

    public record PronunciationItem(String audioId, String accent, String mime, long durationMs) {}

    public record PronunciationView(String status, String word, String source, String audioDatasetId,
                                    String audioDatasetVersion, List<PronunciationItem> items) {}

    public record LookupResponse(UUID lookupId, Instant expiresAt, String status, String queryKey,
                                 DatasetView dataset, String resolvedLemmaKey, JsonNode entry,
                                 List<JsonNode> regions, SourceView source,
                                 PronunciationView pronunciations, String audioError,
                                 Object favoriteState) {}
}
