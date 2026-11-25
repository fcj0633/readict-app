package com.readict.favorites.application;

import static com.readict.favorites.infrastructure.persistence.FavoriteRepository.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import com.readict.favorites.infrastructure.persistence.FavoriteRepository;
import com.readict.shared.web.ApiProblem;

@Service
public class FavoriteQueries {
    private final FavoriteRepository db;
    public FavoriteQueries(FavoriteRepository db) { this.db=db; }
    public Map<String,Object> list(UUID user,String view,String q,String source,String sort,String cursor,int limit) {
        if(!Set.of("words","sentences").contains(view) || !Set.of("recent","old").contains(sort) || limit<1 || limit>100 || q.length()>100) FavoriteService.invalid();
        UUID origin=null;
        if(!source.isEmpty()) try { origin=UUID.fromString(source); } catch(IllegalArgumentException error) { FavoriteService.invalid(); }
        boolean words=view.equals("words");
        Map<String,Object> filter=new HashMap<>();
        filter.put("words",words); filter.put("recent",sort.equals("recent")); filter.put("user",bytes(user));
        filter.put("pattern",q.isEmpty()?null:"%"+escape(q)+"%"); filter.put("origin",bytes(origin));
        filter.put("limit",limit+1);
        String scope=HexFormat.of().formatHex(FavoriteService.hash(user+"|"+view+"|"+sort+"|"+q+"|"+source));
        if(!cursor.isEmpty()) {
            try {
                if(cursor.length()>512) throw new IllegalArgumentException();
                String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
                if(parts.length!=3 || !parts[0].equals(scope)) throw new IllegalArgumentException();
                var at=java.sql.Timestamp.from(Instant.parse(parts[1])); var key=bytes(UUID.fromString(parts[2]));
                filter.put("afterAt",at); filter.put("afterId",key);
            } catch(RuntimeException error) { throw new ApiProblem(400,"INVALID_REQUEST","分页位置无效，请刷新列表"); }
        }
        var rows=db.list(filter);
        boolean more=rows.size()>limit;
        if(more) rows=rows.subList(0,limit);
        String next=null;
        if(more) {
            var last=rows.getLast();
            next=Base64.getUrlEncoder().withoutPadding().encodeToString((scope+"|"+time(last,"created_at")+"|"+id(last,"id")).getBytes(StandardCharsets.UTF_8));
        }
        return map("items",rows.stream().map(words?this::word:this::context).toList(),"nextCursor",next);
    }
    static String escape(String text) { return text.replace("!","!!").replace("%","!%").replace("_","!_"); }
    public Map<String,Object> stats(UUID user) {
        return db.stats(user);
    }
    public Map<String,Object> detail(UUID user,UUID favorite) {
        var row=db.word(user,favorite,false);
        if(row==null) throw FavoriteService.missing();
        row.put("context_count",db.count(favorite));
        var contexts=db.contexts(user,favorite);
        return map("favorite",word(row),"contexts",contexts.stream().map(this::context).toList());
    }
    public Map<String,Object> sources(UUID user) {
        var rows=db.sources(user);
        return map("items",rows.stream().map(r -> map("originKey",id(r,"origin_key"),"bookId",id(r,"book_id"),"bookTitle",text(r,"book_title_snapshot"),"sourceAvailable",truth(r.get("available")),"contextCount",r.get("context_count"),"lastCreatedAt",time(r,"last_created_at"))).toList());
    }
    private Map<String,Object> word(Map<String,Object> r) {
        return map("id",id(r,"id"),"lemma",text(r,"lemma"),"firstSurface",text(r,"first_surface"),"ipa",text(r,"ipa_snapshot"),"pos",text(r,"pos_snapshot"),"meaning",text(r,"meaning_snapshot"),"contextCount",r.get("context_count"),"createdAt",time(r,"created_at"),
                "dictionaryProvider",text(r,"dictionary_provider"),"dictionaryVersion",text(r,"dictionary_version"),"datasetId",id(r,"dictionary_dataset_id"),"entryKey",text(r,"entry_key"),"senseKey",text(r,"sense_key"),"lemmaMethod",text(r,"lemma_method"),"normalizationVersion",text(r,"normalization_version"));
    }
    private Map<String,Object> context(Map<String,Object> r) {
        boolean available=truth(r.get("available"));
        Object anchor=available?map("artifactId",id(r,"active_artifact_id"),"artifactHash",HexFormat.of().formatHex((byte[])r.get("artifact_hash")),"pipelineVersion",text(r,"pipeline_version"),"spineHref",text(r,"spine_href"),"cfi",text(r,"word_range_cfi"),"blockId",text(r,"block_id"),"blockOffset",r.get("sentence_start"),
                "quote",map("exact",text(r,"sentence_snapshot"),"prefix",text(r,"quote_prefix"),"suffix",text(r,"quote_suffix"))):null;
        return map("contextId",id(r,"id"),"favoriteId",id(r,"favorite_word_id"),"lemma",text(r,"lemma"),"sentence",text(r,"sentence_snapshot"),"surface",text(r,"surface_snapshot"),"wordStart",r.get("word_start"),"wordEnd",r.get("word_end"),"bookTitle",text(r,"book_title_snapshot"),"chapterTitle",text(r,"chapter_title_snapshot"),"sourceKey",id(r,"origin_key"),"sourceAvailable",available,"bookId",id(r,"book_id"),"anchor",anchor,"translation",text(r,"translation_snapshot"),"createdAt",time(r,"created_at"));
    }
    private static boolean truth(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()!=0; }
    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
}
