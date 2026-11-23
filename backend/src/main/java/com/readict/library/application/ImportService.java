package com.readict.library.application;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import com.readict.identity.domain.ReadictPrincipal;
import com.readict.library.domain.*;
import com.readict.library.infrastructure.persistence.entity.*;
import com.readict.library.infrastructure.persistence.mapper.*;
import com.readict.library.infrastructure.storage.BookStorage;
import com.readict.shared.web.ApiProblem;

/** Upload handling: bounded staging write, idempotency, same-source reuse and task creation. */
@Service
public class ImportService {
    public record Outcome(UUID bookId, UUID importId, boolean reused, boolean created,
                          boolean adoptSource, boolean discardStaging, String state, String stage,
                          String errorCode, String errorMessage) {}

    private final BookMapper books;
    private final ImportTaskMapper tasks;
    private final BookStorage storage;
    private final ImportSettings settings;
    private final EpubLimits limits;
    private final TransactionTemplate tx;

    public ImportService(BookMapper books, ImportTaskMapper tasks, BookStorage storage,
                         ImportSettings settings, EpubLimits limits, TransactionTemplate tx) {
        this.books = books;
        this.tasks = tasks;
        this.storage = storage;
        this.settings = settings;
        this.limits = limits;
        this.tx = tx;
    }

    public Outcome upload(ReadictPrincipal principal, MultipartFile file, String idempotencyKey) {
        UUID requestId = parseKey(idempotencyKey);
        String filename = safeFilename(file.getOriginalFilename());
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".epub")) {
            throw new ApiProblem(415, "EPUB_FILE_TYPE_REQUIRED", "请选择 .epub 格式的电子书");
        }
        if (!storage.configured()) throw new ApiProblem(503, "STORAGE_NOT_CONFIGURED", "本地存储未配置");
        if (storage.usableBytes() < limits.maxSourceBytes() * 2) {
            throw new ApiProblem(503, "STORAGE_UNAVAILABLE", "本地存储空间不足");
        }
        UUID importId = UUID.randomUUID();
        BookStorage.Uploaded uploaded;
        try (InputStream in = file.getInputStream()) {
            uploaded = storage.writeUpload(importId, in, limits.maxSourceBytes());
        } catch (IOException ex) {
            storage.deleteTree(importId == null ? null : storage.stagingDir(importId));
            throw new ApiProblem(400, "INVALID_REQUEST", "上传内容读取失败");
        }
        Path staging = storage.stagingDir(importId);
        try {
            if (!zipHeader(uploaded.path())) {
                throw new ApiProblem(422, "EPUB_FILE_TYPE_REQUIRED", "请选择 .epub 格式的电子书");
            }
            byte[] requestHash = requestHash(uploaded.sha256(), filename, uploaded.size());
            String finalFilename = filename;
            Outcome outcome = tx.execute(status -> persist(principal.userId(), requestId, requestHash,
                    uploaded, finalFilename));
            if (outcome == null) throw new IllegalStateException("transaction returned no outcome");
            if (outcome.adoptSource()) {
                try {
                    storage.adoptSource(uploaded.path(), principal.userId(), outcome.bookId());
                } catch (ApiProblem problem) {
                    markQueuedFailure(outcome.importId(), "STORAGE_WRITE_FAILED", "源文件保存失败");
                    throw problem;
                }
            }
            if (outcome.discardStaging()) storage.deleteTree(staging);
            return outcome;
        } catch (ApiProblem problem) {
            storage.deleteTree(staging);
            throw problem;
        } catch (RuntimeException ex) {
            storage.deleteTree(staging);
            throw ex;
        }
    }

    private Outcome persist(UUID userId, UUID requestId, byte[] requestHash,
                            BookStorage.Uploaded uploaded, String filename) {
        ImportTaskEntity existing = tasks.findByRequest(userId, requestId);
        if (existing != null) {
            if (!MessageDigest.isEqual(existing.getRequestHash(), requestHash)) {
                throw new ApiProblem(409, "IDEMPOTENCY_KEY_REUSED", "请求编号已用于其他导入");
            }
            return new Outcome(existing.getBookId(), existing.getId(), true, false, false, true,
                    existing.getState(), existing.getStage(), existing.getErrorCode(), existing.getSafeErrorMessage());
        }
        BookEntity byHash = books.findBySource(userId, uploaded.sha256());
        Instant now = Instant.now();
        if (byHash != null) {
            switch (byHash.getStatus()) {
                case "READY" -> {
                    return new Outcome(byHash.getId(), null, true, false, false, true, "SUCCEEDED", "PUBLISHING", null, null);
                }
                case "IMPORTING" -> {
                    ImportTaskEntity current = tasks.currentForBook(byHash.getId());
                    return new Outcome(byHash.getId(), current == null ? null : current.getId(), true, false,
                            false, true, current == null ? "QUEUED" : current.getState(),
                            current == null ? "VALIDATING" : current.getStage(),
                            current == null ? null : current.getErrorCode(),
                            current == null ? null : current.getSafeErrorMessage());
                }
                case "DELETING" -> throw new ApiProblem(409, "BOOK_DELETING", "该书正在删除");
                default -> {
                    if (books.reimport(byHash.getId(), uploaded.size(), filename, now) != 1) {
                        throw new ApiProblem(409, "BOOK_STATE_CONFLICT", "书籍状态已变化，请刷新书架");
                    }
                    enforceLimits(userId);
                    ImportTaskEntity task = newTask(userId, byHash.getId(), requestId, requestHash, now);
                    tasks.insert(task);
                    return new Outcome(byHash.getId(), task.getId(), false, true, true, false,
                            task.getState(), task.getStage(), null, null);
                }
            }
        }
        enforceLimits(userId);
        BookEntity book = new BookEntity();
        UUID bookId = UUID.randomUUID();
        book.setId(bookId);
        book.setUserId(userId);
        book.setOriginKey(UUID.randomUUID());
        book.setSourceHash(uploaded.sha256());
        book.setSourceSize(uploaded.size());
        book.setTitle(titleFromFilename(filename));
        book.setOriginalFilename(filename);
        book.setStatus("IMPORTING");
        book.setLastReadAt(now);
        book.setCreatedAt(now);
        book.setUpdatedAt(now);
        books.insert(book);
        ImportTaskEntity task = newTask(userId, bookId, requestId, requestHash, now);
        tasks.insert(task);
        return new Outcome(bookId, task.getId(), false, true, true, false,
                task.getState(), task.getStage(), null, null);
    }

    private void enforceLimits(UUID userId) {
        if (tasks.countActive(userId) >= settings.perUserLimit()) {
            throw new ApiProblem(429, "IMPORT_LIMIT_REACHED", "正在导入的书过多，请等待当前任务完成", 30);
        }
        if (tasks.countQueued() >= settings.queueCapacity()) {
            throw new ApiProblem(429, "IMPORT_LIMIT_REACHED", "导入队列已满，请稍后重试", 30);
        }
    }

    private ImportTaskEntity newTask(UUID userId, UUID bookId, UUID requestId, byte[] requestHash, Instant now) {
        ImportTaskEntity task = new ImportTaskEntity();
        task.setId(UUID.randomUUID());
        task.setUserId(userId);
        task.setBookId(bookId);
        task.setState("QUEUED");
        task.setStage("VALIDATING");
        task.setAttempt(0);
        task.setRequestId(requestId);
        task.setRequestHash(requestHash);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    private void markQueuedFailure(UUID importId, String code, String message) {
        if (importId == null) return;
        tx.executeWithoutResult(status -> {
            Instant now = Instant.now();
            ImportTaskEntity task = tasks.selectById(importId);
            if (task == null) return;
            tasks.failQueued(importId, code, message, now);
            books.markFailed(task.getBookId(), now);
        });
    }

    public ImportTaskEntity task(ReadictPrincipal principal, UUID importId) {
        ImportTaskEntity task = tasks.findOwned(importId, principal.userId());
        if (task == null) throw new ApiProblem(404, "IMPORT_NOT_FOUND", "导入任务不存在");
        return task;
    }

    public BookEntity book(ReadictPrincipal principal, UUID bookId) {
        BookEntity book = books.findOwned(bookId, principal.userId());
        if (book == null) throw new ApiProblem(404, "BOOK_NOT_FOUND", "书籍不存在");
        return book;
    }

    public Outcome requestDelete(ReadictPrincipal principal, UUID bookId) {
        Outcome outcome = tx.execute(status -> {
            BookEntity book = books.lockOwned(bookId, principal.userId());
            if (book == null) throw new ApiProblem(404, "BOOK_NOT_FOUND", "书籍不存在");
            Instant now = Instant.now();
            if ("DELETING".equals(book.getStatus())) {
                return new Outcome(bookId, null, true, false, false, false, "DELETING", "PUBLISHING", null, null);
            }
            if (books.markDeleting(bookId, principal.userId(), now) == 1) {
                tasks.markCleanupPending(bookId);
                return new Outcome(bookId, null, false, true, false, false, "DELETING", "PUBLISHING", null, null);
            }
            throw new ApiProblem(409, "BOOK_STATE_CONFLICT", "书籍状态已变化，请刷新书架");
        });
        if (outcome == null) throw new ApiProblem(409, "BOOK_STATE_CONFLICT", "书籍状态已变化");
        return outcome;
    }

    private static UUID parseKey(String key) {
        if (key == null || key.isBlank()) throw new ApiProblem(400, "IDEMPOTENCY_KEY_REQUIRED", "缺少请求编号");
        try { return UUID.fromString(key.trim()); }
        catch (IllegalArgumentException ex) { throw new ApiProblem(400, "INVALID_IDEMPOTENCY_KEY", "请求编号格式不正确"); }
    }

    private static String safeFilename(String original) {
        String name = original == null ? "book.epub" : original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) name = "book.epub";
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static String titleFromFilename(String filename) {
        String title = filename.toLowerCase(Locale.ROOT).endsWith(".epub")
                ? filename.substring(0, filename.length() - 5) : filename;
        title = title.trim();
        if (title.isEmpty()) title = "未命名书籍";
        return title.length() > 512 ? title.substring(0, 512) : title;
    }

    private static boolean zipHeader(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(4);
            return head.length == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        } catch (IOException ex) {
            return false;
        }
    }

    private static byte[] requestHash(byte[] sourceHash, String filename, long size) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sourceHash);
            digest.update(filename.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(Long.toString(size).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return digest.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
