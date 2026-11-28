package com.readict.favorites.api;

import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.readict.identity.api.AuthController;
import com.readict.favorites.application.*;
import com.readict.lexical.api.DictionaryLookupDtos.*;
import com.readict.shared.web.ApiProblem;

@RestController
@RequestMapping("/api/v1/favorites")
public class FavoriteController {
    private final FavoriteService favorites;
    private final FavoriteQueries queries;
    private final FavoritePronunciationService pronunciations;
    public FavoriteController(FavoriteService favorites,FavoriteQueries queries,FavoritePronunciationService pronunciations) {
        this.favorites=favorites; this.queries=queries; this.pronunciations=pronunciations;
    }
    private UUID user(Authentication auth) { return AuthController.principal(auth).userId(); }
    private <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    @GetMapping("/state") public ResponseEntity<?> state(Authentication auth,@RequestParam UUID lookupId,@RequestParam String senseId) { return ok(favorites.state(user(auth),lookupId,senseId)); }
    @PostMapping("/state-source") public ResponseEntity<?> sourceState(Authentication auth,@RequestBody FavoriteService.SourceRequest body) { return ok(favorites.stateSource(user(auth),body)); }
    @PostMapping public ResponseEntity<?> save(Authentication auth,@RequestHeader("Idempotency-Key") String key,@RequestBody FavoriteService.SaveRequest body) { return ok(favorites.save(user(auth),key(key),body)); }
    @GetMapping public ResponseEntity<?> list(Authentication auth,@RequestParam(defaultValue="words") String view,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String source,@RequestParam(defaultValue="recent") String sort,@RequestParam(defaultValue="") String cursor,@RequestParam(defaultValue="20") int limit) { return ok(queries.list(user(auth),view,q,source,sort,cursor,limit)); }
    @GetMapping("/sources") public ResponseEntity<?> sources(Authentication auth) { return ok(queries.sources(user(auth))); }
    @GetMapping("/stats") public ResponseEntity<?> stats(Authentication auth) { return ok(queries.stats(user(auth))); }
    @GetMapping("/{id}/pronunciations") public ResponseEntity<PronunciationView> pronunciations(Authentication auth,@PathVariable UUID id) { return ok(pronunciations.pronunciations(user(auth),id)); }
    @GetMapping("/{id}") public ResponseEntity<?> detail(Authentication auth,@PathVariable UUID id) { return ok(queries.detail(user(auth),id)); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(Authentication auth,@PathVariable UUID id) { favorites.delete(user(auth),id); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(); }
    private UUID key(String value) {
        try { UUID id=UUID.fromString(value); if(!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException(); return id; }
        catch(IllegalArgumentException error) { throw new ApiProblem(400,"INVALID_REQUEST","请求编号格式不正确"); }
    }
}
