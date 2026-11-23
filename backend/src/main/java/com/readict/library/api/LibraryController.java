package com.readict.library.api;

import java.nio.file.*;
import java.util.*;
import java.util.Set;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.readict.identity.api.AuthController;
import com.readict.identity.domain.ReadictPrincipal;
import com.readict.library.application.*;
import com.readict.library.infrastructure.persistence.entity.ImportTaskEntity;
import com.readict.shared.web.ApiProblem;

@RestController
@RequestMapping("/api/v1/books")
public class LibraryController {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED");
    private final ImportService imports;
    private final BookQueryService query;

    public LibraryController(ImportService imports, BookQueryService query) {
        this.imports = imports;
        this.query = query;
    }

    @GetMapping
    public ResponseEntity<BookQueryService.ShelfView> shelf(
            Authentication auth,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return noStore(query.shelf(principal(auth), q, cursor, limit));
    }

    @PostMapping("/imports")
    public ResponseEntity<ImportView> upload(Authentication auth,
                                             @RequestPart(name = "file", required = false) MultipartFile file,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (file == null) throw new ApiProblem(400, "INVALID_REQUEST", "请选择 EPUB 文件");
        ImportService.Outcome outcome = imports.upload(principal(auth), file, key);
        ImportTaskEntity task = outcome.importId() == null ? null : imports.task(principal(auth), outcome.importId());
        ImportView view = task == null
                ? new ImportView(null, outcome.bookId(), outcome.state(), outcome.stage(), 0, null,
                        null, null, true)
                : toView(task, outcome.reused());
        HttpStatus status = outcome.reused() && "SUCCEEDED".equals(outcome.state()) ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(view);
    }

    @GetMapping("/imports/{importId}")
    public ResponseEntity<ImportView> importStatus(Authentication auth, @PathVariable UUID importId) {
        return noStore(toView(imports.task(principal(auth), importId), false));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookQueryService.BookView> book(Authentication auth, @PathVariable UUID id) {
        return noStore(query.book(principal(auth), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(Authentication auth, @PathVariable UUID id,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        ImportService.Outcome outcome = imports.requestDelete(principal(auth), id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(Map.of("bookId", outcome.bookId(), "state", "DELETING"));
    }

    @GetMapping("/{id}/cover")
    public ResponseEntity<byte[]> cover(Authentication auth, @PathVariable UUID id,
                                        @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        BookQueryService.Cover cover = query.cover(principal(auth), id);
        if (matches(ifNoneMatch, cover.etag())) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(cover.etag()).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(cover.mime()))
                .contentLength(cover.bytes().length).eTag(cover.etag())
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(cover.bytes());
    }

    @GetMapping("/{id}/reader-manifest")
    public ResponseEntity<BookQueryService.ManifestView> manifest(Authentication auth, @PathVariable UUID id) {
        return noStore(query.manifest(principal(auth), id));
    }

    @GetMapping("/{id}/artifacts/{artifactId}/epub")
    public ResponseEntity<Resource> epub(Authentication auth, @PathVariable UUID id, @PathVariable UUID artifactId,
                                         @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        BookQueryService.Download download = query.epub(principal(auth), id, artifactId);
        if (matches(ifNoneMatch, download.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(download.etag()).build();
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/epub+zip"))
                .contentLength(download.length()).eTag(download.etag())
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"book.epub\"")
                .body(new FileSystemResource(download.path()));
    }

    @GetMapping("/{id}/artifacts/{artifactId}/text-index")
    public ResponseEntity<BookQueryService.TextIndexView> textIndex(Authentication auth, @PathVariable UUID id,
                                                                    @PathVariable UUID artifactId,
                                                                    @RequestParam(required = false) String spineHref) {
        return noStore(query.textIndex(principal(auth), id, artifactId, spineHref));
    }

    private ImportView toView(ImportTaskEntity task, boolean reused) {
        ImportView.Error error = task.getErrorCode() == null ? null
                : new ImportView.Error(task.getErrorCode(), task.getSafeErrorMessage(), !TERMINAL.contains(task.getState()));
        return new ImportView(task.getId(), task.getBookId(), task.getState(), task.getStage(),
                task.getAttempt(), error, task.getCreatedAt(), task.getUpdatedAt(), reused);
    }

    private static boolean matches(String header, String etag) {
        if (header == null || etag == null) return false;
        return Arrays.stream(header.split(",")).map(String::trim).anyMatch(value -> value.equals("*") || value.equals(etag));
    }

    private static ReadictPrincipal principal(Authentication auth) { return AuthController.principal(auth); }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
