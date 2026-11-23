package com.readict.library;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.readict.identity.api.UserView;
import com.readict.identity.application.RegistrationService;
import com.readict.library.infrastructure.epub.EpubFixtures;
import com.readict.support.TestDatabaseInitializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end M03 acceptance against readict_test: real Session/CSRF, multipart upload,
 * worker pipeline, manifest/index/download, cross-user isolation and deletion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "readict.auth.rate-hmac-key=library-integration-rate-secret-0000000000000000",
        "readict.verification.hmac-key=library-integration-code-secret-0000000000000000",
        "readict.library.import.timeout-seconds=30"})
@ContextConfiguration(initializers = TestDatabaseInitializer.class)
class LibraryIT {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path STORAGE = createStorage();

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("readict.library.storage-root", STORAGE::toString);
    }

    private static Path createStorage() {
        try {
            Path root = Files.createTempDirectory("readict-library-it").resolve("readict_test");
            Files.createDirectories(root);
            return root;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @LocalServerPort int port;
    @Autowired DataSource dataSource;
    @Autowired RegistrationService registration;
    @TempDir Path temp;
    private final Set<UUID> users = new HashSet<>();
    private JdbcTemplate jdbc;

    @BeforeEach void verifyDatabase() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("readict_test");
            assertThat(connection.getMetaData().getUserName()).startsWith("readict_test_app@");
        }
    }

    @AfterEach void cleanup() throws Exception {
        for (UUID userId : users) {
            jdbc.update("UPDATE book SET active_artifact_id=NULL WHERE user_id=?", bytes(userId));
            jdbc.update("DELETE c FROM book_chapter c JOIN book_artifact a ON a.id=c.artifact_id "
                    + "JOIN book b ON b.id=a.book_id WHERE b.user_id=?", bytes(userId));
            jdbc.update("DELETE a FROM book_artifact a JOIN book b ON b.id=a.book_id WHERE b.user_id=?", bytes(userId));
            jdbc.update("DELETE FROM import_task WHERE user_id=?", bytes(userId));
            jdbc.update("DELETE FROM book WHERE user_id=?", bytes(userId));
            jdbc.update("DELETE FROM user_settings WHERE user_id=?", bytes(userId));
            jdbc.update("DELETE FROM app_user WHERE id=?", bytes(userId));
        }
        users.clear();
    }

    @Test void importsServesManifestIndexAndEnforcesOwnership() throws Exception {
        Browser owner = signedIn();
        Path epub = EpubFixtures.epub3(temp.resolve("book.epub"));
        HttpResponse<String> upload = owner.upload(epub, UUID.randomUUID().toString());
        assertThat(upload.statusCode()).isEqualTo(202);
        String bookId = JSON.readTree(upload.body()).path("bookId").asString();
        String importId = JSON.readTree(upload.body()).path("importId").asString();
        JsonNode task = awaitTerminal(owner, importId);
        assertThat(task.path("state").asString()).isEqualTo("SUCCEEDED");

        JsonNode shelf = JSON.readTree(owner.get("/books").body());
        assertThat(shelf.path("items")).hasSize(1);
        JsonNode item = shelf.path("items").get(0);
        assertThat(item.path("id").asString()).isEqualTo(bookId);
        assertThat(item.path("status").asString()).isEqualTo("READY");
        assertThat(item.path("title").asString()).isEqualTo("Test Book");
        assertThat(item.path("coverUrl").asString()).isEqualTo("/api/v1/books/" + bookId + "/cover");

        JsonNode manifest = JSON.readTree(owner.get("/books/" + bookId + "/reader-manifest").body());
        assertThat(manifest.path("spine")).hasSize(2);
        assertThat(manifest.path("pipelineVersion").asString()).isEqualTo("epub-safe-v1");
        assertThat(manifest.path("settings").path("font").asString()).isEqualTo("Georgia");
        String artifactId = manifest.path("artifactId").asString();

        HttpResponse<String> epubResponse = owner.get("/books/" + bookId + "/artifacts/" + artifactId + "/epub");
        assertThat(epubResponse.statusCode()).isEqualTo(200);
        assertThat(epubResponse.headers().firstValue("Content-Type").orElse("")).contains("application/epub+zip");
        assertThat(epubResponse.body().length()).isPositive();
        String etag = epubResponse.headers().firstValue("ETag").orElse("");
        HttpResponse<String> cached = owner.get("/books/" + bookId + "/artifacts/" + artifactId + "/epub", etag);
        assertThat(cached.statusCode()).isEqualTo(304);

        JsonNode index = JSON.readTree(owner.get("/books/" + bookId + "/artifacts/" + artifactId
                + "/text-index?spineHref=OEBPS/Text/ch1.xhtml").body());
        assertThat(index.path("blocks")).isNotEmpty();
        assertThat(index.path("blocks").get(0).has("text")).isFalse();
        assertThat(index.path("blocks").get(0).path("textHash").asString()).hasSize(64);

        HttpResponse<String> cover = owner.get("/books/" + bookId + "/cover");
        assertThat(cover.statusCode()).isEqualTo(200);
        assertThat(cover.headers().firstValue("Content-Type").orElse("")).contains("image/png");

        Browser stranger = signedIn();
        assertThat(stranger.get("/books/" + bookId).statusCode()).isEqualTo(404);
        assertThat(stranger.get("/books/" + bookId + "/cover").statusCode()).isEqualTo(404);
        assertThat(stranger.get("/books/" + bookId + "/reader-manifest").statusCode()).isEqualTo(404);
        assertThat(stranger.get("/books/" + bookId + "/artifacts/" + artifactId + "/text-index?spineHref=OEBPS/Text/ch1.xhtml")
                .statusCode()).isEqualTo(404);
        assertThat(stranger.get("/books").body()).doesNotContain(bookId);

        assertThat(owner.upload(epub, UUID.randomUUID().toString()).statusCode()).isEqualTo(200);
        HttpResponse<String> deleted = owner.delete("/books/" + bookId);
        assertThat(deleted.statusCode()).isEqualTo(202);
        assertThat(owner.get("/books").body()).doesNotContain(bookId);
        assertThat(owner.get("/books/" + bookId + "/reader-manifest").statusCode()).isEqualTo(409);
    }

    @Test void rejectsMissingCsrfRepeatedKeysAndBrokenPackages() throws Exception {
        Browser owner = signedIn();
        Path epub = EpubFixtures.epub3(temp.resolve("book.epub"));
        String key = UUID.randomUUID().toString();
        assertThat(owner.upload(epub, key).statusCode()).isEqualTo(202);
        HttpResponse<String> withoutCsrf = owner.uploadWithoutCsrf(epub, UUID.randomUUID().toString());
        assertThat(withoutCsrf.statusCode()).isEqualTo(403);

        Browser second = signedIn();
        String otherKey = UUID.randomUUID().toString();
        assertThat(second.upload(EpubFixtures.epub2(temp.resolve("other.epub")), otherKey).statusCode()).isEqualTo(202);
        HttpResponse<String> reused = second.upload(EpubFixtures.epub3(temp.resolve("different.epub")), otherKey);
        assertThat(reused.statusCode()).isNotEqualTo(202);

        Browser third = signedIn();
        HttpResponse<String> broken = third.upload(EpubFixtures.badMimetype(temp.resolve("bad.epub")),
                UUID.randomUUID().toString());
        assertThat(broken.statusCode()).isEqualTo(202);
        JsonNode task = awaitTerminal(third, JSON.readTree(broken.body()).path("importId").asString());
        assertThat(task.path("state").asString()).isEqualTo("FAILED");
        assertThat(task.path("error").path("code").asString()).isEqualTo("EPUB_INVALID_MIMETYPE");
        assertThat(JSON.readTree(third.get("/books").body()).path("items").get(0).path("status").asString())
                .isEqualTo("FAILED");
    }

    private JsonNode awaitTerminal(Browser browser, String importId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode task = JSON.readTree(browser.get("/books/imports/" + importId).body());
            String state = task.path("state").asString();
            if ("SUCCEEDED".equals(state) || "FAILED".equals(state)) return task;
            Thread.sleep(250);
        }
        throw new AssertionError("import did not reach a terminal state");
    }

    private Browser signedIn() throws Exception {
        String email = "it_" + UUID.randomUUID() + "@readict.test";
        UserView user = registration.register("email", email, "abc12345", randomAddress());
        users.add(user.id());
        Browser browser = new Browser();
        browser.csrf();
        assertThat(browser.call("POST", "/auth/login",
                Map.of("identifierType", "email", "identifier", email, "password", "abc12345"), true).statusCode())
                .isEqualTo(200);
        browser.csrf();
        return browser;
    }

    private static String randomAddress() {
        var random = java.util.concurrent.ThreadLocalRandom.current();
        return "10." + random.nextInt(1, 250) + "." + random.nextInt(1, 250) + "." + random.nextInt(1, 250);
    }

    private static byte[] bytes(UUID id) {        return java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
    }

    private final class Browser {
        final CookieManager cookies = new CookieManager();
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10)).build();
        String token;

        HttpResponse<String> call(String method, String path, Object body, boolean csrf) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(30));
            if (csrf && token != null) builder.header("X-CSRF-TOKEN", token);
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        void csrf() throws Exception {
            HttpResponse<String> response = call("GET", "/auth/csrf", null, false);
            assertThat(response.statusCode()).isEqualTo(200);
            token = JSON.readTree(response.body()).path("token").asString();
        }

        HttpResponse<String> get(String path) throws Exception { return call("GET", path, null, false); }

        HttpResponse<String> get(String path, String ifNoneMatch) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                    .header("If-None-Match", ifNoneMatch).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> delete(String path) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                    .header("X-CSRF-TOKEN", token).header("Idempotency-Key", UUID.randomUUID().toString())
                    .DELETE().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> upload(Path epub, String key) throws Exception { return upload(epub, key, true); }

        HttpResponse<String> uploadWithoutCsrf(Path epub, String key) throws Exception {
            return upload(epub, key, false);
        }

        private HttpResponse<String> upload(Path epub, String key, boolean csrf) throws Exception {
            String boundary = "----readict" + UUID.randomUUID();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + epub.getFileName() + "\"\r\nContent-Type: application/epub+zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(Files.readAllBytes(epub));
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/api/v1/books/imports"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Idempotency-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
            if (csrf && token != null) builder.header("X-CSRF-TOKEN", token);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
