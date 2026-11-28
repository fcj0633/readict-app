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
    private final FavoriteLexicalView lexical;
    public FavoriteQueries(FavoriteRepository db,FavoriteLexicalView lexical) { this.db=db; this.lexical=lexical; }

    public Map<String,Object> list(UUID user,String view,String q,String source,String sort,String cursor,int limit) {
        if(!"words".equals(view) || !Set.of("recent","old").contains(sort) || limit<1 || limit>100 || q.length()>100 || source.length()>512)
            FavoriteService.invalid();
        Map<String,Object> filter=new HashMap<>();
        filter.put("recent",sort.equals("recent")); filter.put("user",bytes(user));
        filter.put("pattern",q.isEmpty()?null:"%"+escape(q)+"%");
        filter.put("sourceTitle",source.isEmpty()?null:source);
        filter.put("limit",limit+1);
        String scope=HexFormat.of().formatHex(FavoriteService.hash("cr6|words|"+sort+"|"+q+"|"+source));
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
        return map("items",rows.stream().map(row -> word(row,3)).toList(),"nextCursor",next);
    }

    static String escape(String text) { return text.replace("!","!!").replace("%","!%").replace("_","!_"); }

    public Map<String,Object> stats(UUID user) { return db.stats(user); }

    public Map<String,Object> detail(UUID user,UUID favorite) {
        var row=db.word(user,favorite,false);
        if(row==null) throw FavoriteService.missing();
        var examples=db.examples(user,favorite);
        return map("favorite",word(row,Integer.MAX_VALUE),"examples",examples.stream().map(this::example).toList());
    }

    public Map<String,Object> sources(UUID user) {
        return map("items",db.sources(user).stream().map(row -> map("bookTitle",text(row,"book_title"))).toList());
    }

    private Map<String,Object> word(Map<String,Object> row,int limit) {
        var view=lexical.view(row,limit);
        return map("id",id(row,"id"),"lemma",text(row,"lemma"),"ipa",view.ipa(),"groups",view.groups(),
                "definitionStatus",view.definitionStatus(),"dictionaryChanged",view.dictionaryChanged(),"createdAt",time(row,"created_at"));
    }

    private Map<String,Object> example(Map<String,Object> row) {
        return map("id",id(row,"id"),"sentence",text(row,"sentence_snapshot"),"surface",text(row,"surface_snapshot"),
                "wordStart",row.get("word_start"),"wordEnd",row.get("word_end"),"bookTitle",text(row,"book_title_snapshot"));
    }

    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
}
