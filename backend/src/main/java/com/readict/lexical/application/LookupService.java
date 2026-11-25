package com.readict.lexical.application;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import com.readict.identity.domain.ReadictPrincipal;
import com.readict.lexical.api.DictionaryLookupDtos.*;
import com.readict.lexical.audio.AudioCatalog;
import com.readict.lexical.core.DictionaryJson;
import com.readict.lexical.core.DictionaryJson.DictionaryFailure;
import com.readict.lexical.core.DictionaryProvider;
import com.readict.library.infrastructure.persistence.entity.BookArtifactEntity;
import com.readict.library.infrastructure.persistence.entity.BookEntity;
import com.readict.library.infrastructure.persistence.mapper.BookArtifactMapper;
import com.readict.library.infrastructure.persistence.mapper.BookMapper;
import com.readict.shared.web.ApiProblem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Assembles a real M05 lookup from the M02 provider and the fixed audio snapshot. */
@Service
public class LookupService {
    private final BookMapper books;
    private final BookArtifactMapper artifacts;
    private final LookupSourceValidator sources;
    private final DictionaryProvider dictionary;
    private final AudioCatalog audio;
    private final LookupReceiptStore receipts;

    public LookupService(BookMapper books, BookArtifactMapper artifacts, LookupSourceValidator sources,
                         DictionaryProvider dictionary, AudioCatalog audio, LookupReceiptStore receipts) {
        this.books = books;
        this.artifacts = artifacts;
        this.sources = sources;
        this.dictionary = dictionary;
        this.audio = audio;
        this.receipts = receipts;
    }

    public LookupResponse lookup(ReadictPrincipal principal, LookupRequest request) {
        if (request == null || request.bookId() == null || request.source() == null) {
            throw new ApiProblem(400, "INVALID_REQUEST", "查词请求格式有误");
        }
        if (request.lemmaCandidate() != null && request.lemmaCandidate().length() > 128) {
            throw new ApiProblem(400, "INVALID_REQUEST", "查词请求格式有误");
        }
        BookEntity book = books.findOwned(request.bookId(), principal.userId());
        if (book == null) throw new ApiProblem(404, "BOOK_NOT_FOUND", "书籍不存在");
        if (!"READY".equals(book.getStatus())) throw new ApiProblem(409, "BOOK_NOT_READY", "书籍尚未准备完成");
        BookArtifactEntity artifact = book.getActiveArtifactId() == null ? null : artifacts.selectById(book.getActiveArtifactId());
        if (artifact == null) throw new ApiProblem(409, "BOOK_NOT_READY", "书籍尚未准备完成");
        if ("REVOKED".equals(artifact.getStatus())) throw new ApiProblem(409, "ARTIFACT_REVOKED", "该版本产物已失效");
        if (!"READY".equals(artifact.getStatus())) throw new ApiProblem(409, "BOOK_NOT_READY", "书籍尚未准备完成");

        SourceView source = sources.validate(artifact, request.source());
        DictionaryProvider.LookupResult result;
        try {
            result = dictionary.lookup(source.surface(), request.posHint());
        } catch (DictionaryFailure error) {
            throw mapLookupError(error);
        }
        DatasetView dataset = result.dataset() == null ? null : new DatasetView(
                result.dataset().datasetId(), result.dataset().datasetVersion(), result.dataset().normalizationVersion(),
                result.dataset().provider(), result.dataset().policyVersion());

        List<JsonNode> regions = new ArrayList<>();
        String audioError = null;
        String audioDatasetId = null;
        Map<String, PronunciationView> pronunciationsByWord = new HashMap<>();
        if (result.regions() != null) {
            for (JsonNode original : result.regions()) {
                ObjectNode region = (ObjectNode) original.deepCopy();
                String headword = region.path("headword").asString();
                PronunciationView pronunciations;
                if (dataset == null) {
                    pronunciations = null;
                } else if (pronunciationsByWord.containsKey(headword)) {
                    pronunciations = pronunciationsByWord.get(headword);
                } else {
                    try {
                        AudioCatalog.Pronunciations audioValue = audioDatasetId == null
                                ? audio.find(headword, dataset.normalizationVersion())
                                : audio.findInDataset(headword, dataset.normalizationVersion(), audioDatasetId);
                        if (audioValue.audioDatasetId() != null) audioDatasetId = audioValue.audioDatasetId();
                        pronunciations = pronunciation(audioValue);
                    } catch (DictionaryFailure error) {
                        audioError = audioError == null ? audioCode(error) : audioError;
                        pronunciations = null;
                    }
                    pronunciationsByWord.put(headword, pronunciations);
                }
                if (pronunciations == null) region.putNull("pronunciations");
                else region.set("pronunciations", DictionaryJson.JSON.valueToTree(pronunciations));
                regions.add(region);
            }
        }

        boolean reportable = "FOUND".equals(result.status()) || "AMBIGUOUS".equals(result.status());
        UUID lookupId = null;
        Instant expiresAt = null;        if (reportable) {
            List<String> entryIds = new ArrayList<>();
            List<String> senseIds = new ArrayList<>();
            for (JsonNode region : regions) {
                entryIds.add(region.path("entryId").asString());
                for (JsonNode sense : region.path("senses")) senseIds.add(sense.path("senseId").asString());
            }
            var receipt = receipts.create(principal.userId(), book.getId(), source, result.status(), dataset, entryIds, senseIds);
            lookupId = receipt.id();
            expiresAt = receipt.expiresAt();
        }

        JsonNode entry = "FOUND".equals(result.status()) && !regions.isEmpty() ? regions.get(0) : null;
        PronunciationView pronunciations = null;
        if (entry != null) {
            String headword = entry.path("headword").asString();
            pronunciations = pronunciationsByWord.get(headword);
        }
        return new LookupResponse(lookupId, expiresAt, result.status(), result.queryKey(), dataset,
                result.resolvedLemmaKey(), entry, List.copyOf(regions), source, pronunciations, audioError, null);
    }

    private static PronunciationView pronunciation(AudioCatalog.Pronunciations value) {
        return new PronunciationView(value.status(), value.word(), value.source(), value.audioDatasetId(),
                value.audioDatasetVersion(), value.items().stream()
                .map(item -> new PronunciationItem(item.audioId(), item.accent(), item.mime(), item.durationMs()))
                .toList());
    }

    private static String audioCode(DictionaryFailure error) {
        return error.code.startsWith("AUDIO_") ? error.code : "AUDIO_UNAVAILABLE";
    }

    private static ApiProblem mapLookupError(DictionaryFailure error) {
        return switch (error.code) {
            case "POS_HINT_INVALID" -> new ApiProblem(400, "INVALID_REQUEST", "词性提示无效");
            case "DICTIONARY_NOT_CONFIGURED", "DATASET_NOT_READY", "DICTIONARY_UNAVAILABLE" ->
                    new ApiProblem(503, error.code, "词典暂不可用");
            default -> new ApiProblem(503, "DICTIONARY_UNAVAILABLE", "词典暂不可用");
        };
    }
}
