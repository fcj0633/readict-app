package com.readict.reading.api;

import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.readict.identity.api.AuthController;
import com.readict.reading.application.ProgressService;
import com.readict.shared.web.ApiProblem;

@RestController
@RequestMapping("/api/v1/books/{bookId}/progress")
public class ProgressController {
    public record GetResponse(ReadingProgressView progress) {}

    public record UpdateRequest(Long expectedVersion, UUID requestId, ReadingProgressView.AnchorView anchor) {}

    private final ProgressService service;

    public ProgressController(ProgressService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<GetResponse> get(Authentication auth, @PathVariable UUID bookId) {
        return noStore(new GetResponse(service.get(AuthController.principal(auth), bookId)));
    }

    @PutMapping
    public ResponseEntity<ReadingProgressView> save(Authentication auth, @PathVariable UUID bookId,
                                                    @RequestBody UpdateRequest request) {
        if (request == null) throw new ApiProblem(400, "INVALID_REQUEST", "进度请求格式有误");
        ReadingProgressView view = service.save(AuthController.principal(auth), bookId,
                request.expectedVersion(), request.requestId(), request.anchor());
        return noStore(view);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
