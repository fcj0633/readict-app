package com.readict.reading.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.readict.identity.domain.ReadictPrincipal;
import com.readict.library.infrastructure.persistence.entity.*;
import com.readict.library.infrastructure.persistence.mapper.*;
import com.readict.reading.api.ReadingProgressView;
import com.readict.reading.infrastructure.persistence.ReadingProgressRepository;
import com.readict.reading.infrastructure.persistence.entity.ReadingProgressEntity;
import com.readict.shared.web.ApiProblem;
import tools.jackson.databind.JsonNode;

/** Progress read/write: validates the anchor against the immutable artifact and stores a version. */
@Service
public class ProgressService {
    private final BookMapper books;
    private final BookArtifactMapper artifacts;
    private final BookChapterMapper chapters;
    private final ReadingProgressRepository progress;
    private final ReadingIndexCache indexes;
    private final Clock clock;

    public ProgressService(BookMapper books, BookArtifactMapper artifacts, BookChapterMapper chapters,
                           ReadingProgressRepository progress, ReadingIndexCache indexes, Clock clock) {
        this.books = books;
        this.artifacts = artifacts;
        this.chapters = chapters;
        this.progress = progress;
        this.indexes = indexes;
        this.clock = clock;
    }

    public ReadingProgressView get(ReadictPrincipal principal, UUID bookId) {
        BookEntity book = books.findOwned(bookId, principal.userId());
        if (book == null) throw new ApiProblem(404, "BOOK_NOT_FOUND", "书籍不存在");
        ReadingProgressEntity entity = progress.find(bookId);
        return entity == null ? null : view(bookId, entity);
    }

    @Transactional
    public ReadingProgressView save(ReadictPrincipal principal, UUID bookId, Long expectedVersion,
                                    UUID requestId, ReadingProgressView.AnchorView anchor) {
        ProgressValidation.validateRequest(expectedVersion, requestId);
        if (anchor == null) throw new ApiProblem(400, "INVALID_REQUEST", "进度请求格式有误");
        ProgressValidation.validateAnchor(anchor.cfi(), anchor.spineHref(), anchor.blockId(), anchor.blockOffset(),
                anchor.quote() == null ? null : anchor.quote().exact(),
                anchor.quote() == null ? null : anchor.quote().prefix(),
                anchor.quote() == null ? null : anchor.quote().suffix());

        BookEntity book = books.findOwned(bookId, principal.userId());
        if (book == null) throw new ApiProblem(404, "BOOK_NOT_FOUND", "书籍不存在");
        if (!"READY".equals(book.getStatus())) throw new ApiProblem(409, "BOOK_NOT_READY", "书籍尚未准备完成");
        BookArtifactEntity artifact = book.getActiveArtifactId() == null ? null
                : artifacts.selectById(book.getActiveArtifactId());
        if (artifact == null) throw new ApiProblem(409, "BOOK_NOT_READY", "书籍尚未准备完成");
        if ("REVOKED".equals(artifact.getStatus())) throw new ApiProblem(410, "ARTIFACT_REVOKED", "该版本产物已失效");
        if (!artifact.getId().toString().equals(anchor.artifactId())
                || !hex(artifact.getArtifactHash()).equals(anchor.artifactHash())
                || !artifact.getPipelineVersion().equals(anchor.pipelineVersion())) {
            throw new ApiProblem(422, "ANCHOR_INVALID", "阅读位置与当前文件不一致");
        }
        BookChapterEntity chapter = null;
        for (BookChapterEntity row : chapters.listByArtifact(artifact.getId())) {
            if (row.getSpineHref().equals(anchor.spineHref())) { chapter = row; break; }
        }
        if (chapter == null) throw new ApiProblem(422, "ANCHOR_INVALID", "章节不属于当前文件");

        JsonNode indexChapter = indexes.chapter(artifact, anchor.spineHref());
        JsonNode blocks = indexChapter.path("blocks");
        List<Integer> lengths = new ArrayList<>(blocks.size());
        int blockIndex = -1;
        for (int i = 0; i < blocks.size(); i++) {
            JsonNode block = blocks.get(i);
            lengths.add(block.path("utf16Length").asInt());
            if (anchor.blockId() != null && anchor.blockId().equals(block.path("blockId").asString())) blockIndex = i;
        }
        if (anchor.blockId() != null) {
            if (blockIndex < 0) throw new ApiProblem(422, "ANCHOR_INVALID", "文本块不属于当前章节");
            int blockLength = lengths.get(blockIndex);
            if (anchor.blockOffset() == null || anchor.blockOffset() > blockLength) {
                throw new ApiProblem(422, "ANCHOR_INVALID", "块内偏移超出范围");
            }
            if (anchor.quote() != null) {
                String text = blocks.get(blockIndex).path("text").asString();
                int end = anchor.blockOffset() + anchor.quote().exact().length();
                if (end > text.length() || !text.substring(anchor.blockOffset(), end).equals(anchor.quote().exact())) {
                    throw new ApiProblem(422, "ANCHOR_INVALID", "引文与正文不一致");
                }
            }
        }
        BigDecimal ratio;
        try {
            ratio = ProgressCalculator.ratio(chapter.getCumulativeTextLength(), chapter.getTextLength(),
                    lengths, blockIndex, anchor.blockOffset(), artifact.getTotalUtf16());
        } catch (IllegalArgumentException ex) {
            throw new ApiProblem(422, "ANCHOR_INVALID", "阅读位置不可用");
        }

        byte[] requestHash = requestHash(expectedVersion, anchor);
        Instant now = clock.instant();
        ReadingProgressEntity existing = progress.find(bookId);
        if (existing != null && existing.getLastRequestId().equals(requestId)) {
            if (!MessageDigest.isEqual(existing.getLastRequestHash(), requestHash)) {
                throw new ApiProblem(409, "IDEMPOTENCY_KEY_REUSED", "请求编号已用于其他进度");
            }
            return view(bookId, existing);
        }
        if (existing == null) {
            if (expectedVersion != 0) throw conflict(null);
            ReadingProgressEntity entity = entity(bookId, principal.userId(), anchor, ratio, 1, requestId, requestHash, now);
            progress.insert(entity);
            books.touchRead(bookId, principal.userId(), now);
            return view(bookId, entity);
        }
        if (existing.getVersion() != expectedVersion) throw conflict(existing);
        ReadingProgressEntity entity = entity(bookId, principal.userId(), anchor, ratio,
                existing.getVersion() + 1, requestId, requestHash, now);
        if (!progress.update(entity)) {
            ReadingProgressEntity current = progress.find(bookId);
            if (current != null && current.getLastRequestId().equals(requestId)
                    && MessageDigest.isEqual(current.getLastRequestHash(), requestHash)) {
                return view(bookId, current);
            }
            throw conflict(current);
        }
        books.touchRead(bookId, principal.userId(), now);
        return view(bookId, entity);
    }

    @Transactional
    public void deleteForBook(UUID bookId) {
        progress.deleteByBook(bookId);
    }

    private ProgressConflictException conflict(ReadingProgressEntity existing) {
        return new ProgressConflictException(existing == null ? 0 : existing.getVersion(),
                existing == null ? null : view(existing.getBookId(), existing));
    }

    private ReadingProgressEntity entity(UUID bookId, UUID userId, ReadingProgressView.AnchorView anchor,
                                          BigDecimal ratio, long version, UUID requestId, byte[] hash, Instant now) {
        ReadingProgressEntity entity = new ReadingProgressEntity();
        entity.setBookId(bookId);
        entity.setUserId(userId);
        entity.setArtifactId(UUID.fromString(anchor.artifactId()));
        entity.setSpineHref(anchor.spineHref());
        entity.setCfi(anchor.cfi());
        entity.setBlockId(anchor.blockId());
        entity.setBlockOffset(anchor.blockOffset());
        entity.setQuoteExact(anchor.quote() == null ? null : anchor.quote().exact());
        entity.setQuotePrefix(anchor.quote() == null ? null : anchor.quote().prefix());
        entity.setQuoteSuffix(anchor.quote() == null ? null : anchor.quote().suffix());
        entity.setProgressRatio(ratio);
        entity.setVersion(version);
        entity.setLastRequestId(requestId);
        entity.setLastRequestHash(hash);
        entity.setUpdatedAt(now);
        return entity;
    }

    private ReadingProgressView view(UUID bookId, ReadingProgressEntity entity) {
        ReadingProgressView.QuoteView quote = entity.getQuoteExact() == null ? null
                : new ReadingProgressView.QuoteView(entity.getQuoteExact(), entity.getQuotePrefix(), entity.getQuoteSuffix());
        BookArtifactEntity artifact = artifacts.selectById(entity.getArtifactId());
        String hash = artifact == null ? "" : hex(artifact.getArtifactHash());
        String pipeline = artifact == null ? "" : artifact.getPipelineVersion();
        ReadingProgressView.AnchorView anchor = new ReadingProgressView.AnchorView(
                entity.getArtifactId().toString(), hash, pipeline, entity.getSpineHref(), entity.getCfi(),
                entity.getBlockId(), entity.getBlockOffset(), quote);
        return new ReadingProgressView(bookId, anchor, entity.getProgressRatio(), entity.getVersion(), entity.getUpdatedAt());
    }

    private static byte[] requestHash(long expectedVersion, ReadingProgressView.AnchorView anchor) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(expectedVersion).append('\u0000').append(anchor.artifactId()).append('\u0000')
                .append(anchor.artifactHash()).append('\u0000').append(anchor.pipelineVersion()).append('\u0000')
                .append(anchor.spineHref()).append('\u0000').append(anchor.cfi()).append('\u0000')
                .append(anchor.blockId() == null ? "" : anchor.blockId()).append('\u0000')
                .append(anchor.blockOffset() == null ? "" : anchor.blockOffset()).append('\u0000')
                .append(anchor.quote() == null ? "" : anchor.quote().exact()).append('\u0000')
                .append(anchor.quote() == null ? "" : anchor.quote().prefix()).append('\u0000')
                .append(anchor.quote() == null ? "" : anchor.quote().suffix());
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }
}
