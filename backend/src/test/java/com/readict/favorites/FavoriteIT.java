package com.readict.favorites;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import com.readict.shared.web.ApiProblem;
import com.readict.support.TestDatabaseInitializer;

/** Favorites transactions, HTTP privacy, concurrent mutations and source retention on readict_test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "readict.auth.rate-hmac-key=favorites-integration-rate-secret-0000000000000000",
        "readict.verification.hmac-key=favorites-integration-code-secret-0000000000000000"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ContextConfiguration(initializers = TestDatabaseInitializer.class)
class FavoriteIT {
    private static final String HREF = "OEBPS/Text/ch1.xhtml";
    private static final Path STORAGE = createStorage();

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("readict.library.storage-root", STORAGE::toString);
    }

    private static Path createStorage() {
        try {
            Path root = Files.createTempDirectory("readict-favorites-it").resolve("readict_test");
            Files.createDirectories(root);
            return root;
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    @Autowired DataSource dataSource;
    @Autowired RegistrationService registration;
    @Autowired com.readict.favorites.application.FavoriteService favorites;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.readict.favorites.infrastructure.persistence.FavoriteRepository repository;
    @Autowired com.readict.favorites.application.FavoriteQueries queries;
    @Autowired com.readict.lexical.application.LookupReceiptStore receipts;
    @Autowired com.readict.library.infrastructure.persistence.mapper.BookArtifactMapper artifacts;
    @Autowired com.readict.lexical.application.LookupSourceValidator validator;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    private final Set<String> sessionIds = new HashSet<>();
    @Autowired org.springframework.session.jdbc.JdbcIndexedSessionRepository sessions;
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

    @AfterEach void cleanup() throws Exception {
        verifyDatabase();
        for(String session : sessionIds) sessions.deleteById(session);
        for(UUID user : users) {
            jdbc.update("DELETE FROM mutation_receipt WHERE user_id=?",bytes(user));
            jdbc.update("DELETE FROM user_favorite_word WHERE user_id=?",bytes(user));
        }
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

    private com.readict.favorites.application.FavoriteService.SaveRequest request(Seed seed,int wordStart,String meaning) { return request(seed,wordStart,meaning,"b-1"); }
    private com.readict.favorites.application.FavoriteService.SaveRequest request(Seed seed,int wordStart,String meaning,String block) {
        var source=new com.readict.lexical.api.DictionaryLookupDtos.SourceContext(seed.artifactId(),HREF,block,0,26,
            "The children see children.","children",wordStart,wordStart+8,"epubcfi(/6/2!/4/2/1:"+wordStart+")","epubcfi(/6/2!/4/2/1:0)",
            new com.readict.lexical.api.DictionaryLookupDtos.Quote("The children see children.",""," 100%_!"));
        var normalized=validator.validate(artifacts.selectById(seed.artifactId()),source);
        var dataset=new com.readict.lexical.api.DictionaryLookupDtos.DatasetView("00000000-0000-4000-8000-000000000001","synthetic-v1","col-normalize-v1","synthetic","unverified");
        var receipt=receipts.create(seed.userId(),seed.bookId(),normalized,"FOUND",dataset,List.of("entry"),List.of("sense"),
            List.of(new com.readict.lexical.application.LookupReceiptStore.FavoriteDraft("sense","entry","s0","child","child","FORM","/test/","noun",meaning)));
        return new com.readict.favorites.application.FavoriteService.SaveRequest(receipt.id(),"sense",null,source);
    }
    @Test void concurrentSavesDeduplicateAndPreserveFirstSnapshot() throws Exception {
        Seed seed=seed(); var request=request(seed,4,"first synthetic meaning");
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(20)) {
            var work=new ArrayList<java.util.concurrent.Future<com.readict.favorites.application.FavoriteService.Saved>>();
            for(int i=0;i<20;i++) work.add(executor.submit(() -> { gate.await(); return favorites.save(seed.userId(),UUID.randomUUID(),request); }));
            gate.countDown();
            var results=new ArrayList<com.readict.favorites.application.FavoriteService.Saved>();
            for(var future:work) results.add(future.get(30,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results).extracting(r -> r.favoriteId()).containsOnly(results.getFirst().favoriteId());
            assertThat(results.stream().filter(r -> r.createdWord()).count()).isEqualTo(1);
            assertThat(results.stream().filter(r -> r.createdContext()).count()).isEqualTo(1);
            assertThat(results).allSatisfy(r -> assertThat(r.contextCount()).isEqualTo(1));
        }
        var otherTarget=request(seed,17,"replacement meaning must not win");
        var duplicate=favorites.save(seed.userId(),UUID.randomUUID(),otherTarget);
        assertThat(duplicate.createdContext()).isFalse();
        var detail=queries.detail(seed.userId(),duplicate.favoriteId());
        assertThat(((Map<?,?>)detail.get("favorite")).get("meaning")).isEqualTo("first synthetic meaning");
        assertThat((List<?>)detail.get("contexts")).hasSize(1);
    }
    @Test void receiptsReplayRejectDifferentPayloadAndNeverResurrect() throws Exception {
        Seed seed=seed(); var request=request(seed,4,"first"); UUID key=UUID.randomUUID();
        var result=favorites.save(seed.userId(),key,request);
        assertThat(favorites.save(seed.userId(),key,request).createdContext()).isFalse();
        assertThat(code(() -> favorites.save(seed.userId(),key,request(seed,17,"second")))).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        favorites.delete(seed.userId(),result.favoriteId());
        var replay=favorites.save(seed.userId(),key,request);
        assertThat(replay.resourceState()).isEqualTo("deleted");
        assertThat(replay.favoriteId()).isEqualTo(result.favoriteId());
        assertThat(queries.stats(seed.userId()).get("words")).isEqualTo(0L);
        favorites.delete(seed.userId(),result.favoriteId());
    }
    @Test void detachesSnapshotsAndRejectsNewWritesWhenBookDeleted() throws Exception {
        Seed seed=seed(); var request=request(seed,4,"first");
        var result=favorites.save(seed.userId(),UUID.randomUUID(),request);
        imports.requestDelete(new ReadictPrincipal(seed.userId(),0,Instant.now()),seed.bookId());
        var contexts=(List<Map<String,Object>>)queries.detail(seed.userId(),result.favoriteId()).get("contexts");
        assertThat(contexts).hasSize(1);
        assertThat(contexts.getFirst().get("sourceAvailable")).isEqualTo(false);
        assertThat(contexts.getFirst().get("anchor")).isNull();
        assertThat(contexts.getFirst().get("bookId")).isNull();
        assertThat(contexts.getFirst().get("sentence")).isEqualTo("The children see children.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM favorite_word_context WHERE favorite_word_id=? AND book_id IS NULL AND chapter_id IS NULL",Integer.class,bytes(result.favoriteId()))).isEqualTo(1);
        assertThat(code(() -> favorites.save(seed.userId(),UUID.randomUUID(),request))).isEqualTo("BOOK_DELETING");
        jdbc.update("UPDATE book SET active_artifact_id=NULL WHERE id=?",bytes(seed.bookId()));
        jdbc.update("DELETE FROM book_chapter WHERE artifact_id=?",bytes(seed.artifactId()));
        jdbc.update("DELETE FROM book_artifact WHERE id=?",bytes(seed.artifactId()));
        jdbc.update("DELETE FROM book WHERE id=?",bytes(seed.bookId()));
        assertThat((List<?>)queries.detail(seed.userId(),result.favoriteId()).get("contexts")).hasSize(1);
    }
    @Test void isolatesUsersAndSearchesLiteralWildcardsWithStableCursor() throws Exception {
        Seed seed=seed(); Seed other=seed();
        var first=favorites.save(seed.userId(),UUID.randomUUID(),request(seed,4,"first"));
        var second=favorites.save(other.userId(),UUID.randomUUID(),request(other,4,"other"));
        assertThat(second.favoriteId()).isNotEqualTo(first.favoriteId());
        assertThat(code(() -> queries.detail(other.userId(),first.favoriteId()))).isEqualTo("FAVORITE_NOT_FOUND");
        assertThat(code(() -> favorites.delete(other.userId(),first.favoriteId()))).isEqualTo("FAVORITE_NOT_FOUND");
        assertThat((List<?>)queries.list(seed.userId(),"words","%_!","","recent","",20).get("items")).isEmpty();
        assertThat((List<?>)queries.list(seed.userId(),"sentences","children","","recent","",20).get("items")).hasSize(1);
        assertThat((List<?>)queries.sources(seed.userId()).get("items")).hasSize(1);
        assertThat(code(() -> queries.list(seed.userId(),"words","","","recent","invalid",20))).isEqualTo("INVALID_REQUEST");
        assertThat(code(() -> favorites.state(other.userId(),request(seed,4,"a").lookupId(),"sense"))).isEqualTo("LOOKUP_NOT_FOUND");
    }
    @Test void validatesSourcesAndAppendsUsingExistingEvidence() throws Exception {
        Seed seed=seed(); var request=request(seed,4,"first");
        var saved=favorites.save(seed.userId(),UUID.randomUUID(),request);
        var next=request(seed,17,"ignored");
        var fallback=new com.readict.favorites.application.FavoriteService.SaveRequest(null,null,saved.favoriteId(),next.source());
        assertThat(favorites.save(seed.userId(),UUID.randomUUID(),fallback).createdContext()).isFalse();
        assertThat(favorites.state(seed.userId(),next.lookupId(),"sense").state()).isEqualTo("CONTEXT");
        assertThat(favorites.stateSource(seed.userId(),new com.readict.favorites.application.FavoriteService.SourceRequest(seed.bookId(),next.source())).state()).isEqualTo("CONTEXT");
        assertThat(code(() -> favorites.state(seed.userId(),UUID.randomUUID(),"sense"))).isEqualTo("LOOKUP_EXPIRED");
        assertThat(code(() -> favorites.state(seed.userId(),next.lookupId(),"foreign-sense"))).isEqualTo("INVALID_REQUEST");
        var unconfirmed=receipts.create(seed.userId(),seed.bookId(),validator.validate(artifacts.selectById(seed.artifactId()),request.source()),"NOT_FOUND",null,List.of(),List.of());
        assertThat(code(() -> favorites.save(seed.userId(),UUID.randomUUID(),new com.readict.favorites.application.FavoriteService.SaveRequest(unconfirmed.id(),"sense",null,request.source())))).isEqualTo("FAVORITE_WORD_UNAVAILABLE");
        var s=request.source();
        var bad=new com.readict.lexical.api.DictionaryLookupDtos.SourceContext(s.artifactId(),s.spineHref(),s.blockId(),0,26,s.sentence(),"other",4,12,s.wordRangeCfi(),s.sentenceRangeCfi(),s.quote());
        assertThat(code(() -> favorites.save(seed.userId(),UUID.randomUUID(),new com.readict.favorites.application.FavoriteService.SaveRequest(request.lookupId(),"sense",null,bad)))).isEqualTo("SOURCE_INVALID");
    }
    @Test void saveDeleteCompetitionMaintainsConstraintsAndOldReceiptStaysDeleted() throws Exception {
        Seed seed=seed(); var req=request(seed,4,"first"); UUID key=UUID.randomUUID();
        var saved=favorites.save(seed.userId(),key,req);
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> { gate.await(); return favorites.save(seed.userId(),UUID.randomUUID(),req); });
            var b=pool.submit(() -> { gate.await(); favorites.delete(seed.userId(),saved.favoriteId()); return true; });
            gate.countDown(); a.get(30,java.util.concurrent.TimeUnit.SECONDS); b.get(30,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(favorites.save(seed.userId(),key,req).resourceState()).isEqualTo("deleted");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_word WHERE user_id=?",Integer.class,bytes(seed.userId()))).isBetween(0,1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_word w LEFT JOIN favorite_word_context c ON c.favorite_word_id=w.id WHERE w.user_id=? AND c.id IS NULL",Integer.class,bytes(seed.userId()))).isZero();
    }
    private java.net.http.HttpResponse<String> http(java.net.http.HttpClient client,String method,String path,String body,String token) throws Exception {
        var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/v1"+path));
        if(token!=null) builder.header("X-CSRF-TOKEN",token);
        if(body!=null) builder.header("Content-Type","application/json");
        return client.send(builder.method(method,body==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }
    @Test void httpRequiresAuthenticationCsrfAndReturnsPrivateProjections() throws Exception {
        Seed seed=seed(); var req=request(seed,4,"first");
        var saved=favorites.save(seed.userId(),UUID.randomUUID(),req);
        var client=java.net.http.HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();
        assertThat(http(client,"GET","/favorites",null,null).statusCode()).isEqualTo(401);
        var json=com.readict.lexical.core.DictionaryJson.JSON;
        String token=json.readTree(http(client,"GET","/auth/csrf",null,null).body()).path("token").asString();
        String email=jdbc.queryForObject("SELECT email FROM app_user WHERE id=?",String.class,bytes(seed.userId()));
        assertThat(http(client,"POST","/auth/login","{\"identifierType\":\"email\",\"identifier\":\""+email+"\",\"password\":\"abc12345\"}",token).statusCode()).isEqualTo(200);
        token=json.readTree(http(client,"GET","/auth/csrf",null,null).body()).path("token").asString();
        var list=http(client,"GET","/favorites?view=words",null,null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(json.readTree(list.body()).path("items").size()).isEqualTo(1);
        assertThat(http(client,"GET","/favorites?limit=101",null,null).statusCode()).isEqualTo(400);
        assertThat(http(client,"GET","/favorites/"+saved.favoriteId(),null,null).statusCode()).isEqualTo(200);
        UUID stranger=newUser();
        var other=java.net.http.HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();
        String otherToken=json.readTree(http(other,"GET","/auth/csrf",null,null).body()).path("token").asString();
        String otherEmail=jdbc.queryForObject("SELECT email FROM app_user WHERE id=?",String.class,bytes(stranger));
        assertThat(http(other,"POST","/auth/login","{\"identifierType\":\"email\",\"identifier\":\""+otherEmail+"\",\"password\":\"abc12345\"}",otherToken).statusCode()).isEqualTo(200);
        otherToken=json.readTree(http(other,"GET","/auth/csrf",null,null).body()).path("token").asString();
        trackSession(other); trackSession(client);
        assertThat(http(other,"GET","/favorites/"+saved.favoriteId(),null,null).statusCode()).isEqualTo(404);
        assertThat(http(other,"DELETE","/favorites/"+saved.favoriteId(),null,otherToken).statusCode()).isEqualTo(404);
        assertThat(json.readTree(http(other,"GET","/favorites",null,null).body()).path("items").size()).isZero();
        assertThat(http(client,"DELETE","/favorites/"+saved.favoriteId(),null,null).statusCode()).isEqualTo(403);
        assertThat(http(client,"DELETE","/favorites/"+saved.favoriteId(),null,token).statusCode()).isEqualTo(204);
    }
    private void trackSession(java.net.http.HttpClient client) {
        var cookies=((java.net.CookieManager)client.cookieHandler().orElseThrow()).getCookieStore().getCookies();
        for(var cookie:cookies) if(cookie.getName().equals("readict-dev-session")) {
            try { sessionIds.add(new String(Base64.getDecoder().decode(cookie.getValue()),StandardCharsets.UTF_8)); } catch(IllegalArgumentException ignored) {}
        }
    }
    @Test void appendsDistinctPositionsAndPaginatesWithFilterBoundCursor() throws Exception {
        Seed seed=seed(); var firstReq=request(seed,4,"first");
        var first=favorites.save(seed.userId(),UUID.randomUUID(),firstReq);
        var nextReq=request(seed,4,"ignored","b-2");
        assertThat(favorites.state(seed.userId(),nextReq.lookupId(),"sense").state()).isEqualTo("WORD");
        var next=favorites.save(seed.userId(),UUID.randomUUID(),nextReq);
        assertThat(next.favoriteId()).isEqualTo(first.favoriteId());
        assertThat(next.contextCount()).isEqualTo(2); assertThat(next.createdWord()).isFalse();
        var page=queries.list(seed.userId(),"sentences","","","recent","",1);
        assertThat((List<?>)page.get("items")).hasSize(1); assertThat(page.get("nextCursor")).isNotNull();
        var tail=queries.list(seed.userId(),"sentences","","","recent",(String)page.get("nextCursor"),1);
        assertThat((List<?>)tail.get("items")).hasSize(1); assertThat(tail.get("nextCursor")).isNull();
        assertThat(code(() -> queries.list(seed.userId(),"sentences","different","","recent",(String)page.get("nextCursor"),1))).isEqualTo("INVALID_REQUEST");
        var expiredKey=UUID.randomUUID(); favorites.save(seed.userId(),expiredKey,nextReq);
        jdbc.update("UPDATE mutation_receipt SET expires_at=? WHERE user_id=? AND request_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),bytes(seed.userId()),bytes(expiredKey));
        assertThat(favorites.save(seed.userId(),expiredKey,nextReq).createdContext()).isFalse();
        var plans=jdbc.queryForList("EXPLAIN SELECT * FROM user_favorite_word WHERE user_id=? ORDER BY created_at,id LIMIT 20",bytes(seed.userId()));
        assertThat(plans).isNotEmpty();
    }
    @Test void concurrentDifferentBooksConvergeOnOneLemma() throws Exception {
        Seed first=seed(), second=seed(first.userId());
        var a=request(first,4,"first"); var b=request(second,4,"second");
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one=pool.submit(() -> {gate.await(); return favorites.save(first.userId(),UUID.randomUUID(),a);});
            var two=pool.submit(() -> {gate.await(); return favorites.save(first.userId(),UUID.randomUUID(),b);});
            gate.countDown();
            assertThat(one.get(30,java.util.concurrent.TimeUnit.SECONDS).favoriteId()).isEqualTo(two.get(30,java.util.concurrent.TimeUnit.SECONDS).favoriteId());
        }
        assertThat(queries.stats(first.userId()).get("words")).isEqualTo(1L);
        assertThat(queries.stats(first.userId()).get("contexts")).isEqualTo(2L);
    }
    @Test void contextInsertFailureRollsBackWordAndReceipt() throws Exception {
        Seed seed=seed(); var req=request(seed,4,"first");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("controlled synthetic context failure"))
                .when(repository).insertContext(org.mockito.ArgumentMatchers.any(Object[].class));
        try {
            assertThatThrownBy(() -> favorites.save(seed.userId(),UUID.randomUUID(),req)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(queries.stats(seed.userId()).get("words")).isEqualTo(0L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mutation_receipt WHERE user_id=?",Integer.class,bytes(seed.userId()))).isZero();
        } finally { org.mockito.Mockito.reset(repository); }
    }
    @Test void detachFailureRollsBackBookDeletion() throws Exception {
        Seed seed=seed(); var saved=favorites.save(seed.userId(),UUID.randomUUID(),request(seed,4,"first"));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("controlled detach failure")).when(repository).detachBook(seed.bookId());
        try {
            assertThatThrownBy(() -> imports.requestDelete(new ReadictPrincipal(seed.userId(),0,Instant.now()),seed.bookId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM book WHERE id=?",String.class,bytes(seed.bookId()))).isEqualTo("READY");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM favorite_word_context WHERE favorite_word_id=? AND book_id=?",Integer.class,bytes(saved.favoriteId()),bytes(seed.bookId()))).isEqualTo(1);
        } finally { org.mockito.Mockito.reset(repository); }
    }
    private record Seed(UUID userId, UUID bookId, UUID artifactId, byte[] artifactHash) {}

    private Seed seed() throws Exception { return seed(newUser()); }
    private Seed seed(UUID userId) throws Exception {
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

    private UUID newUser() {
        String email = "it_" + UUID.randomUUID() + "@readict.test";
        var random = java.util.concurrent.ThreadLocalRandom.current();
        String address = "10." + random.nextInt(1, 250) + "." + random.nextInt(1, 250) + "." + random.nextInt(1, 250);
        UserView user = registration.register("email", email, "abc12345", address);
        users.add(user.id());
        return user.id();
    }

    private static String indexJson() throws Exception {
        String text="The children see children. 100%_!";
        var blocks=new ArrayList<Map<String,Object>>();
        for(String block:List.of("b-1","b-2")) blocks.add(Map.of("blockId",block,"text",text,"utf16Length",33,"textHash",hash(text),"sentences",List.of(Map.of("start",0,"end",26,"kind","SENTENCE"),Map.of("start",27,"end",33,"kind","FRAGMENT"))));
        return com.readict.lexical.core.DictionaryJson.json(Map.of("chapters",List.of(Map.of("spineHref",HREF,"spineIndex",0,"totalUtf16",66,"blocks",blocks))));
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
