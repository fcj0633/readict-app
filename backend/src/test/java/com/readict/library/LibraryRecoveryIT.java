package com.readict.library;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.mybatis.spring.SqlSessionTemplate;
import com.readict.identity.api.UserView;
import com.readict.identity.application.RegistrationService;
import com.readict.identity.domain.ReadictPrincipal;
import com.readict.library.application.ImportService;
import com.readict.library.infrastructure.epub.EpubFixtures;
import com.readict.library.infrastructure.persistence.entity.BookChapterEntity;
import com.readict.library.infrastructure.persistence.mapper.*;
import com.readict.support.TestDatabaseInitializer;

/**
 * Recovery invariants: an expired lease can be taken over by a new owner while the old owner
 * can never commit, and a database failure after the artifact was published never yields READY.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "readict.auth.rate-hmac-key=library-recovery-rate-secret-0000000000000000",
        "readict.verification.hmac-key=library-recovery-code-secret-0000000000000000",
        "readict.library.import.timeout-seconds=30"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ContextConfiguration(initializers = TestDatabaseInitializer.class)
class LibraryRecoveryIT {
    private static final Path STORAGE = createStorage();

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("readict.library.storage-root", STORAGE::toString);
    }

    private static Path createStorage() {
        try {
            Path root = Files.createTempDirectory("readict-recovery-it").resolve("readict_test");
            Files.createDirectories(root);
            return root;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Autowired DataSource dataSource;
    @Autowired RegistrationService registration;
    @Autowired ImportService imports;
    @Autowired BookMapper books;
    @Autowired ImportTaskMapper tasks;
    @Autowired BookArtifactMapper artifacts;
    @Autowired SqlSessionTemplate sqlSession;
    @MockitoSpyBean BookChapterMapper chapters;

    private JdbcTemplate jdbc;
    private final Set<UUID> users = new HashSet<>();
    private final Set<UUID> booksCreated = new HashSet<>();

    @BeforeEach void verifyDatabase() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("readict_test");
            assertThat(connection.getMetaData().getUserName()).startsWith("readict_test_app@");
        }
    }

    @AfterEach void cleanup() {
        reset(chapters);
        for (UUID bookId : booksCreated) {
            jdbc.update("UPDATE book SET active_artifact_id=NULL WHERE id=?", bytes(bookId));
            jdbc.update("DELETE c FROM book_chapter c JOIN book_artifact a ON a.id=c.artifact_id WHERE a.book_id=?", bytes(bookId));
            jdbc.update("DELETE FROM book_artifact WHERE book_id=?", bytes(bookId));
            jdbc.update("DELETE FROM import_task WHERE book_id=?", bytes(bookId));
            jdbc.update("DELETE FROM book WHERE id=?", bytes(bookId));
        }
        for (UUID userId : users) {
            jdbc.update("DELETE FROM import_task WHERE user_id=?", bytes(userId));
            jdbc.update("DELETE FROM user_settings WHERE user_id=?", bytes(userId));
            jdbc.update("DELETE FROM app_user WHERE id=?", bytes(userId));
            deleteTree(STORAGE.resolve("users").resolve(userId.toString()));
        }
        users.clear();
        booksCreated.clear();
    }

    @Test void expiredLeaseIsTakenOverAndOldOwnerCannotCommit() throws Exception {
        UUID userId = newUser();
        byte[] epub = Files.readAllBytes(EpubFixtures.epub3(Files.createTempDirectory("epub").resolve("book.epub")));
        UUID bookId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID staleToken = UUID.randomUUID();
        booksCreated.add(bookId);
        seedRunningTask(userId, bookId, taskId, staleToken, epub, true);

        assertThat(awaitStatus(bookId, "READY", Duration.ofSeconds(60)))
                .as("expired task must be recovered and published").isTrue();
        assertThat(tasks.selectById(taskId).getState()).isEqualTo("SUCCEEDED");
        assertThat(tasks.succeed(taskId, staleToken, Instant.now()))
                .as("old owner must not be able to commit after takeover").isZero();
        assertThat(tasks.fail(taskId, staleToken, "LEASE_LOST", "late", Instant.now())).isZero();
    }

    @Test void postPublishDatabaseFailureNeverLeavesReadyAndRetries() throws Exception {
        UUID userId = newUser();
        byte[] epub = Files.readAllBytes(EpubFixtures.epub3(Files.createTempDirectory("epub").resolve("book.epub")));
        BookChapterMapper raw = sqlSession.getMapper(BookChapterMapper.class);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("controlled chapter insert failure"))
                .doAnswer(invocation -> raw.insert((BookChapterEntity) invocation.getArgument(0)))
                .when(chapters).insert(any(BookChapterEntity.class));

        ImportService.Outcome outcome = imports.upload(new ReadictPrincipal(userId, 0, Instant.now()),
                new MockMultipartFile("file", "book.epub", "application/epub+zip", epub),
                UUID.randomUUID().toString());
        booksCreated.add(outcome.bookId());

        assertThat(awaitStatus(outcome.bookId(), "READY", Duration.ofSeconds(90)))
                .as("retry after the controlled failure must still reach READY").isTrue();
        Integer artifactsInDatabase = jdbc.queryForObject(
                "SELECT COUNT(*) FROM book_artifact WHERE book_id=?", Integer.class, bytes(outcome.bookId()));
        assertThat(artifactsInDatabase).isEqualTo(1);
        Path artifactRoot = STORAGE.resolve("users").resolve(userId.toString()).resolve("artifacts");
        long directories;
        try (var children = Files.list(artifactRoot)) {
            directories = children.filter(Files::isDirectory).count();
        }
        assertThat(directories)
                .as("failed attempt published files then rolled back, leaving one orphan for M08")
                .isEqualTo(2);
        assertThat(tasks.selectById(outcome.importId()).getAttempt()).isGreaterThanOrEqualTo(2);
    }

    private UUID newUser() {
        String email = "it_" + UUID.randomUUID() + "@readict.test";
        var random = java.util.concurrent.ThreadLocalRandom.current();
        String address = "10." + random.nextInt(1, 250) + "." + random.nextInt(1, 250) + "." + random.nextInt(1, 250);
        UserView user = registration.register("email", email, "abc12345", address);
        users.add(user.id());
        return user.id();
    }

    private void seedRunningTask(UUID userId, UUID bookId, UUID taskId, UUID leaseToken,
                                 byte[] epub, boolean expiredLease) throws Exception {
        Instant now = Instant.now();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(epub);
        Path source = STORAGE.resolve("users").resolve(userId.toString()).resolve("books")
                .resolve(bookId.toString()).resolve("source.epub");
        Files.createDirectories(source.getParent());
        Files.write(source, epub);
        jdbc.update("INSERT INTO book(id,user_id,origin_key,source_hash,source_size,title,author,original_filename,"
                        + "status,active_artifact_id,last_read_at,created_at,updated_at) VALUES(?,?,?,?,?,?,NULL,?,?,NULL,?,?,?)",
                bytes(bookId), bytes(userId), bytes(UUID.randomUUID()), hash, (long) epub.length, "Recovery Book",
                "book.epub", "IMPORTING", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));
        Instant leaseUntil = expiredLease ? now.minusSeconds(60) : now.plusSeconds(120);
        jdbc.update("INSERT INTO import_task(id,user_id,book_id,state,stage,attempt,next_attempt_at,lease_token,"
                        + "lease_until,request_id,request_hash,error_code,safe_error_message,cleanup_pending,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?,NULL,?,?,?,?,NULL,NULL,FALSE,?,?)",
                bytes(taskId), bytes(userId), bytes(bookId), "RUNNING", "PARSING", 1,
                bytes(leaseToken), java.sql.Timestamp.from(leaseUntil), bytes(UUID.randomUUID()),
                MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
    }

    private boolean awaitStatus(UUID bookId, String expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String status = jdbc.queryForObject("SELECT status FROM book WHERE id=?", String.class, bytes(bookId));
            if (expected.equals(status)) return true;
            if ("FAILED".equals(status)) return false;
            Thread.sleep(200);
        }
        return false;
    }

    private static void deleteTree(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(item -> {
                try { Files.deleteIfExists(item); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
