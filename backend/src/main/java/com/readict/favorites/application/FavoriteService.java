package com.readict.favorites.application;

import static com.readict.favorites.infrastructure.persistence.FavoriteRepository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.readict.favorites.infrastructure.persistence.FavoriteRepository;
import com.readict.lexical.application.*;
import com.readict.lexical.api.DictionaryLookupDtos.*;
import com.readict.lexical.core.*;
import com.readict.library.infrastructure.persistence.entity.*;
import com.readict.library.infrastructure.persistence.mapper.*;
import com.readict.shared.web.ApiProblem;

@Service
public class FavoriteService {
    public record SaveRequest(UUID lookupId, String senseId, UUID favoriteId, SourceContext source) {}
    public record State(String state, UUID favoriteId, UUID contextId, int contextCount) {}
    public record Saved(UUID favoriteId, UUID contextId, String lemmaKey, String state,
                        boolean createdWord, boolean createdContext, int contextCount, String resourceState) {}
    private final FavoriteRepository db;
    private final BookMapper books;
    private final BookArtifactMapper artifacts;
    private final BookChapterMapper chapters;
    private final LookupSourceValidator sources;
    private final LookupReceiptStore receipts;
    private final DictionaryProvider dictionary;
    private final Clock clock;
    private final TransactionTemplate tx;
    public FavoriteService(FavoriteRepository db, BookMapper books, BookArtifactMapper artifacts,
                           BookChapterMapper chapters, LookupSourceValidator sources, LookupReceiptStore receipts,
                           DictionaryProvider dictionary, Clock clock, TransactionTemplate template) {
        this.db=db; this.books=books; this.artifacts=artifacts; this.chapters=chapters;
        this.sources=sources; this.receipts=receipts; this.dictionary=dictionary; this.clock=clock;
        this.tx=new TransactionTemplate(template.getTransactionManager());
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private LookupReceiptStore.FavoriteDraft draft(LookupReceiptStore.Receipt receipt, String sense) {
        if(!Set.of("FOUND","AMBIGUOUS").contains(receipt.status()))
            throw new ApiProblem(409,"FAVORITE_WORD_UNAVAILABLE","当前词条未确认，请重新查词后收藏");
        return receipt.favorites().stream().filter(d -> d.senseId().equals(sense)).findFirst()
                .orElseThrow(() -> new ApiProblem(400,"INVALID_REQUEST","请选择有效词义"));
    }
    public State state(UUID user, UUID lookup, String sense) {
        var receipt=receipts.find(lookup,user);
        var draft=draft(receipt,sense);
        var word=db.lemma(user,draft.lemmaKey(),false);
        if(word==null) return new State("NONE",null,null,0);
        UUID favorite=id(word,"id");
        BookEntity book=books.findOwned(receipt.bookId(),user);
        if(book==null) throw new ApiProblem(404,"BOOK_NOT_FOUND","书籍不存在");
        var context=db.context(favorite,ContextKey.of(book.getOriginKey(),receipt.source()));
        return new State(context==null?"WORD":"CONTEXT",favorite,context==null?null:id(context,"id"),db.count(favorite));
    }
    public Saved save(UUID user, UUID request, SaveRequest body) {
        if(body==null || body.source()==null || (body.lookupId()==null)==(body.favoriteId()==null)
                || (body.lookupId()!=null && (body.senseId()==null || body.senseId().isBlank()))) invalid();
        byte[] hash=hash(DictionaryJson.json(body));
        // Resolve receipt/dictionary once, outside the retried transaction. A committed replay needs neither.
        var previous=receipt(user,request);
        if(previous!=null && time(previous,"expires_at").isAfter(clock.instant())) return replay(user,hash,previous);
        LookupReceiptStore.Receipt lookup=body.lookupId()==null?null:receipts.find(body.lookupId(),user);
        var selected=lookup==null?null:draft(lookup,body.senseId());
        UUID bookId=lookup==null?bookForSource(user,body.source().artifactId()):lookup.bookId();
        String confirmed=null;
        if(lookup==null) {
            var existing=db.word(user,body.favoriteId(),false);
            if(existing==null) throw missing();
            String expected=text(existing,"lemma_key");
            if(confirms(existing,body.source().surface())) confirmed=expected;
            else try {
                var result=dictionary.lookup(body.source().surface(),null);
                if(expected.equals(result.resolvedLemmaKey())) confirmed=expected;
            } catch(DictionaryJson.DictionaryFailure ignored) { /* No speculative form guessing. */ }
            if(confirmed==null) throw new ApiProblem(409,"LEMMA_UNCONFIRMED","无法确认当前词形属于该收藏");
        }
        String lemma=confirmed;
        for(int attempt=0;;attempt++) {
            try { return tx.execute(status -> persist(user,request,hash,body,lookup,selected,bookId,lemma)); }
            catch(TransientDataAccessException | DuplicateKeyException error) {
                if(attempt>=2) throw error;
                try { Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(10,40)); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw error; }
            }
        }
    }
    private boolean confirms(Map<String,Object> word,String surface) {
        String key=normalize(surface), expected=text(word,"lemma_key");
        if(expected.equals(key)) return true;
        // First surface was already confirmed by the original lookup. Preserve that evidence offline.
        if(normalize(text(word,"first_surface")).equals(key)) return true;
        UUID dataset=id(word,"dictionary_dataset_id");
        if(dataset==null) return false;
        var row=db.formEvidence(bytes(dataset),key,expected);
        return ((Number)row.get("n")).intValue()>0;
    }
    public record SourceRequest(UUID bookId,SourceContext source) {}
    public State stateSource(UUID user,SourceRequest request) {
        if(request==null || request.bookId()==null || request.source()==null) invalid();
        var book=books.findOwned(request.bookId(),user);
        if(book==null) throw new ApiProblem(404,"BOOK_NOT_FOUND","书籍不存在");
        if(!"READY".equals(book.getStatus())) throw new ApiProblem(409,"BOOK_NOT_READY","书籍当前不可收藏");
        var artifact=artifacts.selectById(book.getActiveArtifactId());
        if(artifact==null || !"READY".equals(artifact.getStatus())) throw new ApiProblem(409,"BOOK_NOT_READY","书籍当前不可收藏");
        var source=sources.validate(artifact,request.source());
        var candidates=db.matchWords(bytes(user),normalize(source.surface()),source.surface(),normalize(source.surface()));
        var matches=candidates.stream().filter(w -> confirms(w,source.surface())).toList();
        if(matches.size()!=1) throw new ApiProblem(409,"FAVORITE_WORD_UNAVAILABLE","当前词条未确认，请重新查词后收藏");
        UUID favorite=id(matches.getFirst(),"id");
        var context=db.context(favorite,ContextKey.of(book.getOriginKey(),source));
        return new State(context==null?"WORD":"CONTEXT",favorite,context==null?null:id(context,"id"),db.count(favorite));
    }
    private UUID bookForSource(UUID user,UUID artifact) {
        if(artifact==null) { invalid(); }
        var row=db.bookForSource(bytes(artifact),bytes(user));
        if(row==null) throw new ApiProblem(404,"BOOK_NOT_FOUND","书籍不存在");
        return id(row,"id");
    }
    private Saved persist(UUID user,UUID request,byte[] hash,SaveRequest body,LookupReceiptStore.Receipt lookup,
                          LookupReceiptStore.FavoriteDraft draft,UUID bookId,String confirmed) {
        BookEntity book=books.lockOwned(bookId,user);
        if(book==null) throw new ApiProblem(404,"BOOK_NOT_FOUND","书籍不存在");
        var previous=receipt(user,request);
        if(previous!=null && time(previous,"expires_at").isAfter(clock.instant())) return replay(user,hash,previous);
        if(!"READY".equals(book.getStatus())) throw new ApiProblem(409,"DELETING".equals(book.getStatus())?"BOOK_DELETING":"BOOK_NOT_READY","书籍当前不可收藏");
        var artifact=artifacts.selectById(book.getActiveArtifactId());
        if(artifact==null || !"READY".equals(artifact.getStatus())) throw new ApiProblem(409,"BOOK_NOT_READY","书籍当前不可收藏");
        var source=sources.validate(artifact,body.source());
        if(lookup!=null && !source.equals(lookup.source())) throw new ApiProblem(422,"SOURCE_INVALID","收藏来源与查词位置不一致");
        var chapter=chapters.listByArtifact(artifact.getId()).stream().filter(c -> c.getSpineHref().equals(source.spineHref())).findFirst()
                .orElseThrow(() -> new ApiProblem(422,"SOURCE_INVALID","收藏章节不存在"));
        String lemma=draft==null?confirmed:draft.lemmaKey();
        var word=db.lemma(user,lemma,true);
        boolean createdWord=false;
        Instant now=clock.instant();
        if(word==null) {
            if(draft==null) throw missing();
            if(draft.meaning().length()>10000 || draft.lemmaKey().length()>128 || draft.lemma().length()>128
                    || (draft.ipa()!=null && draft.ipa().length()>256)) throw new ApiProblem(422,"SOURCE_TOO_LONG","词条快照超出长度限制");
            UUID favorite=UUID.randomUUID();
            try {
                db.insertWord(bytes(favorite),bytes(user),lemma,draft.lemma(),source.surface(),draft.ipa(),draft.pos(),draft.meaning(),
                    lookup.dataset().provider(),lookup.dataset().datasetVersion(),bytes(UUID.fromString(lookup.dataset().datasetId())),
                    lookup.dataset().datasetVersion(),draft.entryKey(),draft.senseKey(),draft.lemmaMethod(),lookup.dataset().normalizationVersion(),java.sql.Timestamp.from(now));
                createdWord=true;
            } catch(DuplicateKeyException error) { if(db.lemma(user,lemma,true)==null) throw error; }
            word=db.lemma(user,lemma,true);
        }
        UUID favorite=id(word,"id");
        if(body.favoriteId()!=null && !body.favoriteId().equals(favorite)) throw missing();
        byte[] key=ContextKey.of(book.getOriginKey(),source);
        var context=db.context(favorite,key);
        boolean createdContext=context==null;
        UUID contextId=context==null?UUID.randomUUID():id(context,"id");
        if(context==null) db.insertContext(bytes(contextId),bytes(favorite),bytes(bookId),bytes(chapter.getId()),bytes(book.getOriginKey()),book.getSourceHash(),artifact.getArtifactHash(),source.pipelineVersion(),key,
            source.spineHref(),source.blockId(),source.sentenceStart(),source.sentenceEnd(),source.contextKind(),source.sentence(),source.surface(),source.wordStart(),source.wordEnd(),
            source.wordRangeCfi(),source.sentenceRangeCfi(),source.quote().prefix(),source.quote().suffix(),book.getTitle(),chapter.getTitle()==null?source.spineHref():chapter.getTitle(),java.sql.Timestamp.from(now));
        var result=new Saved(favorite,contextId,lemma,"CONTEXT",createdWord,createdContext,db.count(favorite),"active");
        if(previous!=null) db.expireReceipt(bytes(user),bytes(request));
        db.insertReceipt(bytes(user),bytes(request),hash,bytes(favorite),bytes(contextId),DictionaryJson.json(result),java.sql.Timestamp.from(now.plusSeconds(86400)),java.sql.Timestamp.from(now));
        return result;
    }
    private Map<String,Object> receipt(UUID user,UUID request) {
        return db.receipt(bytes(user),bytes(request));
    }
    private Saved replay(UUID user,byte[] hash,Map<String,Object> receipt) {
        if(!MessageDigest.isEqual(hash,(byte[])receipt.get("request_hash"))) throw new ApiProblem(409,"IDEMPOTENCY_KEY_REUSED","请求编号已用于其他收藏");
        UUID favorite=id(receipt,"favorite_id");
        var word=db.word(user,favorite,false);
        var json=DictionaryJson.parse(text(receipt,"result_json"));
        boolean active=word!=null;
        return new Saved(favorite,id(receipt,"context_id"),json.path("lemmaKey").asString(),active?"CONTEXT":"NONE",false,false,
                active?db.count(favorite):0,active?"active":"deleted");
    }
    public void delete(UUID user,UUID favorite) {
        tx.executeWithoutResult(status -> {
            var word=db.word(user,favorite,true);
            if(word==null) {
                if(db.existingWord(bytes(favorite))!=null) throw missing();
                return;
            }
            db.deleteWord(bytes(favorite),bytes(user));
            db.markDeleted(bytes(user),bytes(favorite));
        });
    }
    static String normalize(String value) {
        try { return DictionaryJson.normalize(value); }
        catch(RuntimeException error) { return ""; }
    }
    static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static ApiProblem missing() { return new ApiProblem(404,"FAVORITE_NOT_FOUND","收藏不存在"); }
    static void invalid() { throw new ApiProblem(400,"INVALID_REQUEST","收藏请求格式有误"); }
}
