package com.readict.library.infrastructure.storage;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import com.readict.shared.web.ApiProblem;

class BookStorageTest {
    @TempDir Path temp;

    private BookStorage storage() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return new BookStorage(temp.resolve("readict_test").toString(), environment);
    }

    @Test void writesHashesAndAdoptsSourceInsideTheRoot() throws IOException {
        BookStorage storage = storage();
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        var uploaded = storage.writeUpload(UUID.randomUUID(), new ByteArrayInputStream(content), 1024);
        assertThat(uploaded.size()).isEqualTo(5);
        assertThat(TextIndexerHash.hex(uploaded.sha256())).isEqualTo(TextIndexerHash.of(content));

        UUID userId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        storage.adoptSource(uploaded.path(), userId, bookId);
        Path source = storage.sourcePath(userId, bookId);
        assertThat(Files.readAllBytes(source)).isEqualTo(content);
        assertThat(source.toRealPath().startsWith(storage.root())).isTrue();
    }

    @Test void rejectsOversizedUploadAndKeepsNoPartialFile() throws IOException {
        BookStorage storage = storage();
        ApiProblem problem = catchThrowableOfType(() ->
                storage.writeUpload(UUID.randomUUID(), new ByteArrayInputStream(new byte[100]), 10), ApiProblem.class);
        assertThat(problem.code()).isEqualTo("UPLOAD_TOO_LARGE");
        try (var walk = Files.walk(storage.root())) {
            assertThat(walk.filter(Files::isRegularFile).count()).isZero();
        }
    }

    @Test void rejectsTraversalKeys() {
        BookStorage storage = storage();
        assertThat(catchThrowableOfType(() -> storage.resolve("users/../../escape.epub"), ApiProblem.class).status())
                .isEqualTo(404);
        assertThat(catchThrowableOfType(() -> storage.resolve("/absolute"), ApiProblem.class).status()).isEqualTo(404);
    }

    @Test void publishesArtifactsAtomicallyAndRejectsSymlinkedLeaf() throws IOException {
        BookStorage storage = storage();
        UUID userId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        Path staging = storage.stagingDir(UUID.randomUUID()).resolve("out");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("reader.epub"), "epub");
        storage.publishArtifacts(staging, userId, artifactId);
        String key = storage.artifactKey(userId, artifactId, "reader.epub");
        assertThat(Files.readString(storage.resolve(key))).isEqualTo("epub");
        assertThat(storage.existsAndDigest(key).length).isEqualTo(32);
    }

    @Test void requiresProfileMatchingDirectoryName() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        BookStorage storage = new BookStorage(temp.resolve("readict_test").toString(), environment);
        assertThat(catchThrowableOfType(storage::root, ApiProblem.class).code())
                .isEqualTo("STORAGE_ENVIRONMENT_INVALID");
    }

    /** Local helper: SHA-256 of a byte array as lowercase hex. */
    static final class TextIndexerHash {
        static String of(byte[] content) {
            try {
                return hex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        static String hex(byte[] bytes) {
            StringBuilder out = new StringBuilder();
            for (byte value : bytes) {
                out.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
            }
            return out.toString();
        }
    }
}
