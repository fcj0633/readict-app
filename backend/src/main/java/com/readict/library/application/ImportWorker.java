package com.readict.library.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.readict.library.domain.*;
import com.readict.library.infrastructure.epub.*;
import com.readict.library.infrastructure.persistence.entity.*;
import com.readict.library.infrastructure.persistence.mapper.*;
import com.readict.library.infrastructure.storage.BookStorage;
import com.readict.shared.web.ApiProblem;

/**
 * Database-driven import worker: claims one task under a lease, builds the artifact outside a
 * transaction, publishes the directory atomically and only then commits READY.
 */
@Service
public class ImportWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ImportWorker.class);
    private final ImportTaskMapper tasks;
    private final BookMapper books;
    private final BookArtifactMapper artifacts;
    private final BookChapterMapper chapters;
    private final BookStorage storage;
    private final EpubPipeline pipeline;
    private final ImportSettings settings;
    private final TransactionTemplate tx;
    private final ThreadPoolTaskExecutor executor;
    private final java.time.Clock clock;
    private final AtomicInteger inFlight = new AtomicInteger();

    public ImportWorker(ImportTaskMapper tasks, BookMapper books, BookArtifactMapper artifacts,
                        BookChapterMapper chapters, BookStorage storage, EpubPipeline pipeline,
                        ImportSettings settings, java.time.Clock clock, TransactionTemplate tx,
                        ThreadPoolTaskExecutor executor) {
        this.tasks = tasks;
        this.books = books;
        this.artifacts = artifacts;
        this.chapters = chapters;
        this.storage = storage;
        this.pipeline = pipeline;
        this.settings = settings;
        this.clock = clock;
        this.tx = tx;
        this.executor = executor;
    }

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        recover();
        if (!storage.configured()) return;
        if (inFlight.get() >= settings.concurrency()) return;
        UUID taskId = tx.execute(status -> claimOne());
        if (taskId == null) return;
        inFlight.incrementAndGet();
        try {
            executor.execute(() -> {
                try { process(taskId); }
                finally { inFlight.decrementAndGet(); }
            });
        } catch (RejectedExecutionException ex) {
            inFlight.decrementAndGet();
            Instant now = clock.instant();
            tx.executeWithoutResult(status -> {
                ImportTaskEntity task = tasks.selectById(taskId);
                if (task != null) {
                    tasks.requeue(taskId, task.getLeaseToken(), now.plusSeconds(30), now);
                }
            });
        }
    }

    private UUID claimOne() {
        Instant now = clock.instant();
        UUID candidate = tasks.candidate(now);
        if (candidate == null) return null;
        UUID token = UUID.randomUUID();
        Instant until = now.plusSeconds(settings.leaseSeconds());
        return tasks.claim(candidate, token, until, now) == 1 ? candidate : null;
    }

    private void recover() {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            tasks.requeueExpired(now, now.plusSeconds(10), settings.maxAttempts());
            tasks.failExpired(now);
            books.markFailedWithoutOpenTask(now);
        });
    }

    private void process(UUID taskId) {
        ImportTaskEntity task = tasks.selectById(taskId);
        if (task == null || !"RUNNING".equals(task.getState()) || task.getBookId() == null) return;
        BookEntity book = books.selectById(task.getBookId());
        if (book == null) {
            finish(task, "BOOK_NOT_FOUND", "书籍不存在");
            return;
        }
        UUID token = task.getLeaseToken();
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "book-import-lease-" + taskId);
            thread.setDaemon(true);
            return thread;
        });
        renewer.scheduleAtFixedRate(() -> {
            Instant now = clock.instant();
            try {
                if (tasks.renew(taskId, token, now.plusSeconds(settings.leaseSeconds()), now) != 1) {
                    leaseLost.set(true);
                }
            } catch (RuntimeException ex) {
                leaseLost.set(true);
            }
        }, settings.renewSeconds(), settings.renewSeconds(), TimeUnit.SECONDS);

        Path stagingRoot = null;
        try {
            Path source = storage.sourcePath(task.getUserId(), book.getId());
            stagingRoot = storage.stagingDir(taskId);
            Path out = Files.createTempDirectory(stagingRoot, "out");
            Instant deadline = clock.instant().plusSeconds(settings.timeoutSeconds());
            advance(taskId, token, "PARSING");
            EpubPipeline.Built built = pipeline.build(source, out, hex(book.getSourceHash()), deadline);
            if (leaseLost.get()) throw new ImportFailure("LEASE_LOST", "导入租约已失效");
            advance(taskId, token, "PUBLISHING");
            UUID artifactId = UUID.randomUUID();
            Path published = storage.publishArtifacts(built.directory(), task.getUserId(), artifactId);
            Boolean committed = tx.execute(status -> commit(task, book, built, artifactId, published));
            if (!Boolean.TRUE.equals(committed)) {
                finish(task, "BOOK_STATE_CONFLICT", "书籍状态已变化，请刷新书架");
            }
        } catch (ImportFailure failure) {
            finish(task, failure.code(), failure.getMessage());
        } catch (ApiProblem problem) {
            finish(task, problem.code(), problem.getMessage());
        } catch (Exception ex) {
            LOG.warn("Import failed: importId={} code=IMPORT_EXCEPTION", taskId);
            retryOrFail(task);
        } finally {
            renewer.shutdownNow();
            if (stagingRoot != null) storage.deleteTree(stagingRoot);
        }
    }

    private void advance(UUID taskId, UUID token, String stage) {
        tasks.stage(taskId, token, stage, clock.instant());
    }

    private Boolean commit(ImportTaskEntity task, BookEntity book, EpubPipeline.Built built,
                           UUID artifactId, Path published) {
        Instant now = clock.instant();
        UUID userId = task.getUserId();
        BookArtifactEntity artifact = new BookArtifactEntity();
        artifact.setId(artifactId);
        artifact.setBookId(book.getId());
        artifact.setArtifactHash(built.artifactHash());
        artifact.setPipelineVersion(EpubPipeline.PIPELINE_VERSION);
        artifact.setEpubStorageKey(storage.artifactKey(userId, artifactId, "reader.epub"));
        artifact.setIndexStorageKey(storage.artifactKey(userId, artifactId, "index.json"));
        artifact.setManifestStorageKey(storage.artifactKey(userId, artifactId, "manifest.json"));
        if (built.coverMime() != null) {
            artifact.setCoverStorageKey(storage.artifactKey(userId, artifactId,
                    "image/png".equals(built.coverMime()) ? "cover.png" : "cover.jpg"));
            artifact.setCoverMime(built.coverMime());
            artifact.setCoverHash(built.coverHash());
        }
        artifact.setTotalUtf16(built.totalUtf16());
        artifact.setStatus("READY");
        artifact.setCreatedAt(now);
        artifacts.insert(artifact);

        long cumulative = 0;
        for (EpubPipeline.Chapter chapter : built.chapters()) {
            BookChapterEntity entity = new BookChapterEntity();
            entity.setId(UUID.randomUUID());
            entity.setArtifactId(artifactId);
            entity.setSpineIndex(chapter.spineIndex());
            entity.setSpineHref(chapter.href());
            entity.setHrefHash(sha256(chapter.href()));
            entity.setTitle(chapter.title().length() > 512 ? chapter.title().substring(0, 512) : chapter.title());
            entity.setTextLength(chapter.textLength());
            cumulative += chapter.textLength();
            entity.setCumulativeTextLength(cumulative);
            entity.setCreatedAt(now);
            chapters.insert(entity);
        }
        if (books.markReady(book.getId(), artifactId, now) != 1) {
            throw new ApiProblem(409, "BOOK_STATE_CONFLICT", "书籍状态已变化，请刷新书架");
        }
        books.updateMetadata(book.getId(), built.title(), built.author(), now);
        if (tasks.succeed(task.getId(), task.getLeaseToken(), now) != 1) {
            throw new ApiProblem(409, "LEASE_LOST", "导入租约已失效");
        }
        return Boolean.TRUE;
    }

    private void finish(ImportTaskEntity task, String code, String message) {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            tasks.fail(task.getId(), task.getLeaseToken(), code, safe(message), now);
            if (task.getBookId() != null) books.markFailed(task.getBookId(), now);
        });
    }

    private void retryOrFail(ImportTaskEntity task) {
        ImportTaskEntity current = tasks.selectById(task.getId());
        if (current == null) return;
        if (current.getAttempt() >= settings.maxAttempts()) {
            finish(current, "INTERNAL_ERROR", "导入失败，请稍后重试");
            return;
        }
        long backoff = switch (current.getAttempt()) {
            case 1 -> 10;
            case 2 -> 30;
            default -> 90;
        };
        Instant now = clock.instant();
        tx.executeWithoutResult(status ->
                tasks.requeue(current.getId(), current.getLeaseToken(), now.plusSeconds(backoff), now));
    }

    private static String safe(String message) {
        if (message == null) return "导入失败";
        return message.length() > 255 ? message.substring(0, 255) : message;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
