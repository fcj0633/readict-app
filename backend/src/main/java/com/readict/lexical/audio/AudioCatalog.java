package com.readict.lexical.audio;

import static com.readict.lexical.core.DictionaryJson.*;
import static com.readict.lexical.core.DictionaryImporter.*;
import java.time.*;
import java.util.*;
import com.readict.lexical.infrastructure.persistence.mapper.AudioMapper;
import com.readict.shared.persistence.typehandler.UuidBinaryTypeHandler;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;

@Service
public class AudioCatalog {
    public record Item(String audioId,String accent,String mime,long durationMs) {}
    public record Pronunciations(String status,String word,String source,String audioDatasetId,String audioDatasetVersion,List<Item> items) {}
    private record Cached(Pronunciations value,long expires) {}
    private final AudioMapper db;
    private final Clock clock;
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(32,.75f,true);
    @org.springframework.beans.factory.annotation.Autowired
    public AudioCatalog(AudioMapper db){this(db,Clock.systemUTC());}
    AudioCatalog(AudioMapper db,Clock clock){this.db=db;this.clock=clock;}
    public Pronunciations find(String displayHeadword,String normalizeVersion) {
        check("col-normalize-v1".equals(normalizeVersion),"AUDIO_NORMALIZATION_UNSUPPORTED");String word=normalize(displayHeadword);
        try{
            var active=db.active();if(active==null||active.get("dataset_id")==null)return new Pronunciations("notConfigured",displayHeadword,"local-sound",null,null,List.of());
            check("READY".equals(active.get("status")),"AUDIO_NOT_READY");
            String dataset=UuidBinaryTypeHandler.decode((byte[])active.get("dataset_id")).toString();String key=dataset+":"+word;
            Pronunciations result=get(key);
            if(result==null){
                var manifest=parse((String)active.get("manifest"));var grouped=new TreeMap<String,LinkedHashMap<String,Item>>();
                for(var row:db.find((byte[])active.get("dataset_id"),word)){
                    String accent=(String)row.get("accent"),sha=HexFormat.of().formatHex((byte[])row.get("output_sha"));
                    grouped.computeIfAbsent(accent,x->new LinkedHashMap<>()).putIfAbsent(sha,new Item(UuidBinaryTypeHandler.decode((byte[])row.get("id")).toString(),accent,"audio/mpeg",number(row,"duration_ms")));
                }
                boolean ambiguous=false;var items=new ArrayList<Item>();for(var group:grouped.values()){if(group.size()==1)items.add(group.values().iterator().next());else ambiguous=true;}
                result=new Pronunciations(ambiguous?"ambiguous":items.isEmpty()?"missing":"available",word,"local-sound",dataset,manifest.path("version").asText(),List.copyOf(items));put(key,result);
            }
            return new Pronunciations(result.status(),displayHeadword,result.source(),result.audioDatasetId(),result.audioDatasetVersion(),result.items());
        }catch(DataAccessException e){throw new DictionaryFailure("AUDIO_DATABASE_UNAVAILABLE");}
    }
    private synchronized Pronunciations get(String key){var c=cache.get(key);if(c==null)return null;if(clock.millis()>=c.expires()){cache.remove(key);return null;}return c.value();}
    private synchronized void put(String key,Pronunciations value){cache.put(key,new Cached(value,clock.millis()+300000));while(cache.size()>5000)cache.remove(cache.keySet().iterator().next());}
}
