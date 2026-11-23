package com.readict.library.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import com.readict.identity.domain.ReadictPrincipal;
import com.readict.library.domain.EpubLimits;
import com.readict.library.infrastructure.persistence.entity.*;
import com.readict.library.infrastructure.persistence.mapper.*;
import com.readict.library.infrastructure.storage.BookStorage;
import com.readict.settings.api.Preferences;
import com.readict.settings.application.SettingsService;
import com.readict.shared.web.ApiProblem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.*;

/** Read-side projections for the shelf, manifest, text index, cover and artifact download. */
@Service
public class BookQueryService {
    public record BookView(UUID id, String title, String author, String coverUrl, String status,
                           BigDecimal progressRatio, Instant lastReadAt, Instant createdAt) {}

    public record ShelfView(List<BookView> items, String nextCursor) {}

    public record ChapterView(UUID chapterId, String href, int index, String title, long textLength) {}

    public record TocView(String id, String label, String href, List<TocView> children) {}

    public record ManifestView(UUID bookId, UUID artifactId, String artifactHash, String pipelineVersion,
                               String epubUrl, String textIndexUrl, List<ChapterView> spine, List<TocView> toc,
                               Preferences settings, long settingsVersion) {}

    public record SentenceView(int start, int end, String kind) {}

    public record BlockView(String blockId, int utf16Length, String textHash, List<SentenceView> sentences) {}

    public record TextIndexView(String spineHref, int spineIndex, long totalUtf16, List<BlockView> blocks) {}

    public record Cover(byte[] bytes, String mime, String etag) {}

    public record Download(Path path, String etag, long length) {}

    private final BookMapper books;
    private final BookArtifactMapper artifacts;
    private final BookChapterMapper chapters;
    private final BookStorage storage;
    private final SettingsService settings;
    private final ObjectMapper json;
    private final EpubLimits limits;

    public BookQueryService(BookMapper books, BookArtifactMapper artifacts, BookChapterMapper chapters,
                            BookStorage storage, SettingsService settings, ObjectMapper json, EpubLimits limits) {
        this.books = books;
        this.artifacts = artifacts;
        this.chapters = chapters;
        this.storage = storage;
        this.settings = settings;
        this.json = json;
        this.limits = limits;
    }

    public ShelfView shelf(ReadictPrincipal principal, String q, String cursor, Integer requestedLimit) {
        int limit = requestedLimit == null ? 20 : Math.max(1, Math.min(50, requestedLimit));
        String pattern = null;
        if (q != null && !q.isBlank()) {
            String trimmed = q.trim();
            if (trimmed.length() > 100) throw new ApiProblem(400, "INVALID_REQUEST", "搜索词过长");
            pattern = "%" + escapeLike(trimmed) + "%";
        }
        Cursor decoded = decodeCursor(cursor);
        List<BookEntity> rows = books.shelf(principal.userId(), pattern,
                decoded == null ? null : decoded.time(), decoded == null ? null : decoded.id(), limit + 1);
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        List<BookView> items = new ArrayList<>(rows.size());
        for (BookEntity book : rows) items.add(view(book));
        String next = more && !items.isEmpty() ? encodeCursor(rows.get(rows.size() - 1)) : null;
        return new ShelfView(items, next);
    }

    public BookView book(ReadictPrincipal principal, UUID bookId) {
        BookEntity book = books.findOwned(bookId, principal.userId());
        if (book == null) throw new ApiProblem(404, "BOOK_NOT_FOUND", "书籍不存在");
        return view(book);
    }

    public ManifestView manifest(ReadictPrincipal principal, UUID bookId) {
        BookEntity book = requireReady(principal, bookId);
        BookArtifactEntity artifact = artifacts.selectById(book.getActiveArtifactId());
        if (artifact == null) throw new ApiProblem(409, "BOOK_NOT_READY", "书籍尚未准备完成");
        List<BookChapterEntity> rows = chapters.listByArtifact(artifact.getId());
        List<ChapterView> spine = new ArrayList<>(rows.size());
        for (BookChapterEntity row : rows) {
            spine.add(new ChapterView(row.getId(), row.getSpineHref(), row.getSpineIndex(), row.getTitle(),
                    row.getTextLength()));
        }
        List<TocView> toc = toc(artifact);
        SettingsService.View settingsView;
        try {
            settingsView = settings.get(principal);
        } catch (RuntimeException ex) {
            settingsView = new SettingsService.View(defaults(), 0);
        }
        String base = "/api/v1/books/" + bookId + "/artifacts/" + artifact.getId();
        return new ManifestView(bookId, artifact.getId(), hex(artifact.getArtifactHash()),
                artifact.getPipelineVersion(), base + "/epub", base + "/text-index?spineHref={spineHref}",
                spine, toc, settingsView.preferences(), settingsView.version());
    }

    public TextIndexView textIndex(ReadictPrincipal principal, UUID bookId, UUID artifactId, String spineHref) {
        if (spineHref == null || spineHref.isBlank() || spineHref.length() > 512) {
            throw new ApiProblem(400, "INVALID_REQUEST", "章节参数不合法");
        }
        BookArtifactEntity artifact = requireArtifact(principal, bookId, artifactId);
        BookChapterEntity chapter = null;
        for (BookChapterEntity row : chapters.listByArtifact(artifactId)) {
            if (row.getSpineHref().equals(spineHref)) { chapter = row; break; }
        }
        if (chapter == null) throw new ApiProblem(404, "CHAPTER_NOT_FOUND", "章节不存在");
        JsonNode index = readJson(artifact.getIndexStorageKey());
        JsonNode target = null;
        for (JsonNode node : index.path("chapters")) {
            if (spineHref.equals(node.path("spineHref").asString())) { target = node; break; }
        }
        if (target == null) throw new ApiProblem(404, "CHAPTER_NOT_FOUND", "章节索引不存在");
        List<BlockView> blocks = new ArrayList<>();
        for (JsonNode block : target.path("blocks")) {
            List<SentenceView> sentences = new ArrayList<>();
            for (JsonNode sentence : block.path("sentences")) {
                sentences.add(new SentenceView(sentence.path("start").asInt(), sentence.path("end").asInt(),
                        sentence.path("kind").asString()));
            }
            blocks.add(new BlockView(block.path("blockId").asString(), block.path("utf16Length").asInt(),
                    block.path("textHash").asString(), sentences));
        }
        return new TextIndexView(spineHref, chapter.getSpineIndex(), target.path("totalUtf16").asLong(), blocks);
    }

    public Cover cover(ReadictPrincipal principal, UUID bookId) {
        BookEntity book = requireReady(principal, bookId);
        BookArtifactEntity artifact = artifacts.selectById(book.getActiveArtifactId());
        if (artifact == null || artifact.getCoverStorageKey() == null) {
            throw new ApiProblem(404, "COVER_NOT_FOUND", "该书没有封面");
        }
        Path path = storage.resolve(artifact.getCoverStorageKey());
        try {
            byte[] bytes = Files.readAllBytes(path);
            return new Cover(bytes, artifact.getCoverMime(), "\"" + hex(artifact.getCoverHash()) + "\"");
        } catch (IOException ex) {
            throw new ApiProblem(404, "COVER_NOT_FOUND", "封面不可用");
        }
    }

    public Download epub(ReadictPrincipal principal, UUID bookId, UUID artifactId) {
        BookArtifactEntity artifact = requireArtifact(principal, bookId, artifactId);
        Path path = storage.resolve(artifact.getEpubStorageKey());
        try {
            return new Download(path, "\"" + hex(artifact.getArtifactHash()) + "\"", Files.size(path));
        } catch (IOException ex) {
            throw new ApiProblem(404, "ARTIFACT_NOT_FOUND", "产物不可用");
        }
    }

    private BookArtifactEntity requireArtifact(ReadictPrincipal principal, UUID bookId, UUID artifactId) {
        BookEntity book = requireReady(principal, bookId);
        BookArtifactEntity artifact = artifacts.selectById(artifactId);
        if (artifact == null || !artifact.getBookId().equals(bookId)) {
            throw new ApiProblem(404, "ARTIFACT_NOT_FOUND", "产物不存在");
        }
        if ("REVOKED".equals(artifact.getStatus())) {
            throw new ApiProblem(410, "ARTIFACT_REVOKED", "该版本产物已失效");
        }
        return artifact;
    }

    private BookEntity requireReady(ReadictPrincipal principal, UUID bookId) {
        BookEntity book = books.findOwned(bookId, principal.userId());
        if (book == null) throw new ApiProblem(404, "BOOK_NOT_FOUND", "书籍不存在");
        if (!"READY".equals(book.getStatus()) || book.getActiveArtifactId() == null) {
            throw new ApiProblem(409, "BOOK_NOT_READY", "书籍尚未准备完成");
        }
        return book;
    }

    private BookView view(BookEntity book) {
        String coverUrl = null;
        if ("READY".equals(book.getStatus()) && book.getActiveArtifactId() != null) {
            BookArtifactEntity artifact = artifacts.selectById(book.getActiveArtifactId());
            if (artifact != null && artifact.getCoverStorageKey() != null) {
                coverUrl = "/api/v1/books/" + book.getId() + "/cover";
            }
        }
        return new BookView(book.getId(), book.getTitle(), book.getAuthor(), coverUrl, book.getStatus(),
                book.getProgressRatio(), book.getLastReadAt(), book.getCreatedAt());
    }

    private List<TocView> toc(BookArtifactEntity artifact) {
        JsonNode manifest = readJson(artifact.getManifestStorageKey());
        List<TocView> result = new ArrayList<>();
        int index = 0;
        for (JsonNode node : manifest.path("toc")) {
            result.add(new TocView("toc-" + (++index), node.path("label").asString(),
                    node.path("href").asString(), List.of()));
        }
        return result;
    }

    private JsonNode readJson(String key) {
        Path path = storage.resolve(key);
        try (var in = Files.newInputStream(path)) {
            return json.readTree(in);
        } catch (IOException ex) {
            throw new ApiProblem(503, "STORAGE_UNAVAILABLE", "阅读数据暂不可用");
        }
    }

    private static Preferences defaults() {
        return new Preferences("Georgia", 20, new BigDecimal("1.8"), 660, "warm", "en-GB");
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private record Cursor(Instant time, UUID id) {}

    private static String encodeCursor(BookEntity book) {
        String raw = book.getLastReadAt().toEpochMilli() + "|" + book.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int split = raw.indexOf('|');
            if (split <= 0) return null;
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, split))),
                    UUID.fromString(raw.substring(split + 1)));
        } catch (RuntimeException ex) {
            throw new ApiProblem(400, "INVALID_REQUEST", "分页参数不合法");
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
