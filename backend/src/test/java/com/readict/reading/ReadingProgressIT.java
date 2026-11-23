package com.readict.reading;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.readict.identity.api.UserView;
import com.readict.identity.application.RegistrationService;
import com.readict.identity.domain.ReadictPrincipal;
import com.readict.library.application.BookQueryService;
import com.readict.library.application.ImportService;
import com.readict.reading.api.ReadingProgressView;
import com.readict.reading.application.ProgressConflictException;
import com.readict.reading.application.ProgressService;
import com.readict.shared.web.ApiProblem;
import com.readict.support.TestDatabaseInitializer;

/** Progress persistence, versioning, idempotency and shelf projection against readict_test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "readict.auth.rate-hmac-key=reading-integration-rate-secret-0000000000000000",
        "readict.verification.hmac-key=reading-integration-code-secret-0000000000000000"})
@ContextConfiguration(initializers = TestDatabaseInitializer.class)
class ReadingProgressIT {
    private static final String HREF = "OEBPS/Text/ch1.xhtml";
    private static final Path STORAGE = createStorage();

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("readict.library.storage-root", STORAGE::toString);
    }

    private static Path createStorage() {
        try {
            Path root = Files.createTempDirectory("readict-reading-it").resolve("readict_test");
            Files.createDirectories(root);
            return root;
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    @Autowired DataSource dataSource;
    @Autowired RegistrationService registration;
    @Autowired ProgressService progress;
    @Autowired ImportService imports;
    @Autowired BookQueryService query;
    private JdbcTemplate jdbc;
    private final Set<UUID> users = new HashSet<>();
    private final Set<UUID> books = new HashSet<>();

    @BeforeEach void verifyDatabase() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("readict_test");
            assertThat(connection.getMetaData().getUserName()).startsWith("readict_test_app@");
        }
    }

    @AfterEach void cleanup() {
        for (UUID bookId : books) {
            jdbc.update("UPDATE book SET active_artifact_id=NULL WHERE id=?", bytes(bookId));
            jdbc.update("DELETE FROM reading_progress WHERE book_id=?", bytes(bookId));
            jdbc.update("DELETE FROM book_chapter WHERE artifact_id IN (SELECT id FROM book_artifact WHERE book_id=?)", bytes(bookId));
            jdbc.update("DELETE FROM book_artifact WHERE book_id=?", bytes(bookId));
            jdbc.update("DELETE FROM import_task WHERE book_id=?", bytes(bookId));
            jdbc.update("DELETE FROM book WHERE id=?", bytes(bookId));
        }
        for (UUID userId : users) {
            jdbc.update("DELETE FROM reading_progress WHERE user_id=?", bytes(userId));
            jdbc.update("DELETE FROM user_settings WHERE user_id=?", bytes(userId));
            jdbc.update("DELETE FROM app_user WHERE id=?", bytes(userId));
            deleteTree(STORAGE.resolve("users").resolve(userId.toString()));
        }
        books.clear(); users.clear();
    }

    @Test void savesReadsAndProjectsRatioOnShelf() throws Exception {
        Seed seed = seed();
        ReadictPrincipal principal = new ReadictPrincipal(seed.userId(), 0, Instant.now());
        assertThat(progress.get(principal, seed.bookId())).isNull();

        ReadingProgressView saved = progress.save(principal, seed.bookId(), 0L, UUID.randomUUID(), anchor(seed, "b-2", 1, "e", "d", ""));
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.progressRatio()).isEqualByComparingTo(new BigDecimal("0.1600000"));
        assertThat(saved.anchor().artifactHash()).isEqualTo(hex(seed.artifactHash()));

        ReadingProgressView loaded = progress.get(principal, seed.bookId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.anchor().blockId()).isEqualTo("b-2");
        assertThat(loaded.anchor().quote().exact()).isEqualTo("e");

        var shelf = query.shelf(principal, null, null, 10);
        assertThat(shelf.items()).hasSize(1);
        assertThat(shelf.items().get(0).progressRatio()).isEqualByComparingTo(new BigDecimal("0.1600000"));
        assertThat(jdbc.queryForObject("SELECT last_read_at FROM book WHERE id=?", java.sql.Timestamp.class, bytes(seed.bookId())))
                .isNotNull();
    }

    @Test void keepsVersionMonotonicAndReplaysRequests() throws Exception {
        Seed seed = seed();
        ReadictPrincipal principal = new ReadictPrincipal(seed.userId(), 0, Instant.now());
        UUID requestId = UUID.randomUUID();
        ReadingProgressView first = progress.save(principal, seed.bookId(), 0L, requestId, anchor(seed, "b-1", 0, "a", "", ""));
        ReadingProgressView replay = progress.save(principal, seed.bookId(), 0L, requestId, anchor(seed, "b-1", 0, "a", "", ""));
        assertThat(replay.version()).isEqualTo(first.version());

        ProgressConflictException conflict = catchThrowableOfType(() ->
                progress.save(principal, seed.bookId(), 0L, UUID.randomUUID(), anchor(seed, "b-2", 0, "d", "", "e")),
                ProgressConflictException.class);
        assertThat(conflict.currentVersion()).isEqualTo(1);
        assertThat(conflict.serverProgress()).isNotNull();

        ReadingProgressView second = progress.save(principal, seed.bookId(), 1L, UUID.randomUUID(), anchor(seed, "b-2", 0, "d", "", "e"));
        assertThat(second.version()).isEqualTo(2);
    }

    @Test void rejectsInvalidAnchorsAndForeignBooks() throws Exception {
        Seed seed = seed();
        ReadictPrincipal principal = new ReadictPrincipal(seed.userId(), 0, Instant.now());
        assertThat(code(() -> progress.save(principal, seed.bookId(), 0L, UUID.randomUUID(),
                new ReadingProgressView.AnchorView(seed.artifactId().toString(), "0".repeat(64), "epub-safe-v1",
                        HREF, "epubcfi(/6/2!/4/2/1:0)", null, null, null)))).isEqualTo("ANCHOR_INVALID");
        assertThat(code(() -> progress.save(principal, seed.bookId(), 0L, UUID.randomUUID(),
                anchor(seed, "b-2", 9, "e", "d", "")))).isEqualTo("ANCHOR_INVALID");
        assertThat(code(() -> progress.save(principal, seed.bookId(), 0L, UUID.randomUUID(),
                anchor(seed, "b-2", 1, "x", "d", "")))).isEqualTo("ANCHOR_INVALID");
        assertThat(code(() -> progress.save(principal, seed.bookId(), 0L, UUID.randomUUID(),
                new ReadingProgressView.AnchorView(seed.artifactId().toString(), hex(seed.artifactHash()), "epub-safe-v1",
                        "OEBPS/Text/other.xhtml", "epubcfi(/6/2!/4/2/1:0)", null, null, null)))).isEqualTo("ANCHOR_INVALID");

        Seed other = seed();
        ReadictPrincipal stranger = new ReadictPrincipal(other.userId(), 0, Instant.now());
        assertThat(code(() -> progress.get(stranger, seed.bookId()))).isEqualTo("BOOK_NOT_FOUND");
    }

    @Test void deletesProgressWithTheBook() throws Exception {
        Seed seed = seed();
        ReadictPrincipal principal = new ReadictPrincipal(seed.userId(), 0, Instant.now());
        progress.save(principal, seed.bookId(), 0L, UUID.randomUUID(), anchor(seed, "b-1", 0, "a", "", ""));
        imports.requestDelete(principal, seed.bookId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reading_progress WHERE book_id=?", Integer.class, bytes(seed.bookId())))
                .isZero();
        assertThat(progress.get(principal, seed.bookId())).isNull();
    }

    private record Seed(UUID userId, UUID bookId, UUID artifactId, byte[] artifactHash) {}

    private Seed seed() throws Exception {
        UUID userId = newUser();
        UUID bookId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        UUID origin = UUID.randomUUID();
        byte[] sourceHash = MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        byte[] artifactHash = MessageDigest.getInstance("SHA-256").digest(bookId.toString().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        books.add(bookId);
        String indexKey = "users/" + userId + "/artifacts/" + artifactId + "/index.json";
        String epubKey = "users/" + userId + "/artifacts/" + artifactId + "/reader.epub";
        String manifestKey = "users/" + userId + "/artifacts/" + artifactId + "/manifest.json";
        Path indexFile = STORAGE.resolve(indexKey);
        Files.createDirectories(indexFile.getParent());
        Files.writeString(indexFile, indexJson(), StandardCharsets.UTF_8);
        Files.writeString(STORAGE.resolve(epubKey), "epub", StandardCharsets.UTF_8);
        Files.writeString(STORAGE.resolve(manifestKey), "{}", StandardCharsets.UTF_8);

        jdbc.update("INSERT INTO book(id,user_id,origin_key,source_hash,source_size,title,author,original_filename,"
                        + "status,active_artifact_id,last_read_at,created_at,updated_at) VALUES(?,?,?,?,?,?,NULL,?,?,NULL,?,?,?)",
                bytes(bookId), bytes(userId), bytes(origin), sourceHash, 10L, "Reading Book", "book.epub",
                "READY", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        jdbc.update("INSERT INTO book_artifact(id,book_id,artifact_hash,pipeline_version,epub_storage_key,"
                        + "index_storage_key,manifest_storage_key,cover_storage_key,cover_mime,cover_hash,total_utf16,status,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,NULL,NULL,NULL,?, 'READY', ?)",
                bytes(artifactId), bytes(bookId), artifactHash, "epub-safe-v1", epubKey, indexKey, manifestKey,
                100L, java.sql.Timestamp.from(now));
        jdbc.update("UPDATE book SET active_artifact_id=? WHERE id=?", bytes(artifactId), bytes(bookId));
        byte[] hrefHash = MessageDigest.getInstance("SHA-256").digest(HREF.getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO book_chapter(id,artifact_id,spine_index,spine_href,href_hash,title,text_length,"
                        + "cumulative_text_length,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                bytes(UUID.randomUUID()), bytes(artifactId), 0, HREF, hrefHash, "Chapter 1", 8L, 20L,
                java.sql.Timestamp.from(now));
        return new Seed(userId, bookId, artifactId, artifactHash);
    }

    private ReadingProgressView.AnchorView anchor(Seed seed, String blockId, int offset,
                                                  String exact, String prefix, String suffix) {
        return new ReadingProgressView.AnchorView(seed.artifactId().toString(), hex(seed.artifactHash()),
                "epub-safe-v1", HREF, "epubcfi(/6/2!/4/2/1:" + offset + ")", blockId, offset,
                new ReadingProgressView.QuoteView(exact, prefix, suffix));
    }

    private UUID newUser() {
        String email = "it_" + UUID.randomUUID() + "@readict.test";
        var random = java.util.concurrent.ThreadLocalRandom.current();
        String address = "10." + random.nextInt(1, 250) + "." + random.nextInt(1, 250) + "." + random.nextInt(1, 250);
        UserView user = registration.register("email", email, "abc12345", address);
        users.add(user.id());
        return user.id();
    }

    private static String indexJson() throws Exception {
        String h1 = hash("abc"); String h2 = hash("de"); String h3 = hash("fgh");
        return "{\"chapters\":[{\"spineHref\":\"" + HREF + "\",\"spineIndex\":0,\"totalUtf16\":100,\"blocks\":["
                + "{\"blockId\":\"b-1\",\"text\":\"abc\",\"utf16Length\":3,\"textHash\":\"" + h1 + "\",\"sentences\":[]},"
                + "{\"blockId\":\"b-2\",\"text\":\"de\",\"utf16Length\":2,\"textHash\":\"" + h2 + "\",\"sentences\":[]},"
                + "{\"blockId\":\"b-3\",\"text\":\"fgh\",\"utf16Length\":3,\"textHash\":\"" + h3 + "\",\"sentences\":[]}]}]}";
    }

    private static String hash(String value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        return out.toString();
    }

    private static String code(Runnable action) {
        try { action.run(); return "NO_FAILURE"; }
        catch (ApiProblem problem) { return problem.code(); }
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static void deleteTree(Path path) {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(item -> {
                try { Files.deleteIfExists(item); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
