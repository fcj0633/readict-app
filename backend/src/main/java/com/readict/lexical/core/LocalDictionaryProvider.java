package com.readict.lexical.core;

import static com.readict.lexical.core.DictionaryJson.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.readict.lexical.infrastructure.persistence.mapper.DictionaryMapper;
import com.readict.shared.persistence.typehandler.UuidBinaryTypeHandler;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

@Service
public class LocalDictionaryProvider implements DictionaryProvider {
    private final DictionaryMapper db;
    private final Clock clock;
    private record Cached(LookupResult result,long until,int bytes) {}
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(32,.75f,true);
    private long cacheBytes;
    @org.springframework.beans.factory.annotation.Autowired
    public LocalDictionaryProvider(DictionaryMapper db){this(db,Clock.systemUTC());}
    LocalDictionaryProvider(DictionaryMapper db,Clock clock){this.db=db;this.clock=clock;}
    @Override public LookupResult lookup(String query,String hint) {
        String key;try{key=normalize(query);}catch(DictionaryFailure e){return new LookupResult("UNSUPPORTED_QUERY",null,null,null,JSON.createArrayNode());}
        check(hint==null||Set.of("noun","verb","adjective","adverb","pronoun","preposition","conjunction","determiner","interjection","unknown").contains(hint),"POS_HINT_INVALID");
        try {
            var active=db.active();check(active!=null&&active.get("dataset_id")!=null,"DICTIONARY_NOT_CONFIGURED");check("READY".equals(active.get("status")),"DATASET_NOT_READY");
            byte[] id=(byte[])active.get("dataset_id");var m=parse((String)active.get("manifest"));var ref=new DatasetRef(UuidBinaryTypeHandler.decode(id).toString(),m.path("datasetVersion").asText(),m.path("normalizationVersion").asText(),"local-col",m.path("rights").path("policyVersion").asText());
            String ck=ref.datasetId()+":"+key;LookupResult result=get(ck);if(result==null){result=load(key,id,ref,m);put(ck,result);}return copy(result,hint,query);
        }catch(DataAccessException e){throw new DictionaryFailure("DICTIONARY_UNAVAILABLE");}
    }
    private LookupResult load(String key,byte[] dataset,DatasetRef ref,JsonNode manifest){
        var rows=db.findEntries(dataset,key);var regions=JSON.createArrayNode();if(rows.isEmpty())return new LookupResult("NOT_FOUND",key,ref,null,regions);
        var senses=new HashMap<String,List<JsonNode>>();var ids=rows.stream().map(r->(byte[])r.get("id")).toList();
        // Bound IN parameters while keeping one immutable DatasetRef across chunks.
        for(int offset=0;offset<ids.size();offset+=500)for(var row:db.findSenses(dataset,ids.subList(offset,Math.min(offset+500,ids.size())))){
            String id=UuidBinaryTypeHandler.decode((byte[])row.get("entry_id")).toString();senses.computeIfAbsent(id,x->new ArrayList<>()).add(parse((String)row.get("metadata")));
        }
        Set<String> lemmas=new LinkedHashSet<>();
        for(var row:rows){var e=parse((String)row.get("metadata"));String id=e.path("entryId").asText();var definition=senses.getOrDefault(id,List.of());check(!definition.isEmpty(),"DATASET_NOT_READY");lemmas.add(e.path("lemmaKey").asText());ObjectNode region=JSON.createObjectNode();for(String k:List.of("entryId","entryKey","headword","lemmaKey","lemmaMethod","sectionOrder","componentOrder","parentEntryKey","phonetics","sourceRange","sourceRecordHash"))region.set(k,e.path(k).deepCopy());region.put("datasetId",ref.datasetId());region.put("datasetVersion",ref.datasetVersion());region.put("provider","local-col");region.put("providerVersion",ref.datasetVersion());region.put("lemma",e.path("headword").asText());region.put("regionOrder",regions.size());region.put("licensePolicyVersion",ref.policyVersion());region.put("cachePolicy","memory");region.put("snapshotAllowed",false);region.set("attribution",manifest.path("rights").path("attribution"));var defs=region.putArray("senses");definition.forEach(s->defs.add(s.deepCopy()));var refs=region.putArray("references");for(var r:e.path("references"))if(r.path("usable").asBoolean())refs.add(r.deepCopy());region.set("notes",e.path("notes").deepCopy());regions.add(region);}
        return new LookupResult(lemmas.size()>1?"AMBIGUOUS":"FOUND",key,ref,lemmas.size()==1?lemmas.iterator().next():null,regions);
    }
    private synchronized LookupResult get(String key){var c=cache.get(key);if(c==null)return null;if(clock.millis()>=c.until){cache.remove(key);cacheBytes-=c.bytes;return null;}return c.result;}
    private synchronized void put(String key,LookupResult result){int size=json(result).getBytes(StandardCharsets.UTF_8).length*2+key.getBytes(StandardCharsets.UTF_8).length*2+256;if(size>256*1024)return;long ttl=result.status().equals("NOT_FOUND")?30000:600000;Cached old=cache.put(key,new Cached(result,clock.millis()+ttl,size));if(old!=null)cacheBytes-=old.bytes;cacheBytes+=size;while(cache.size()>2000||cacheBytes>16L*1024*1024){var i=cache.entrySet().iterator();var c=i.next();cacheBytes-=c.getValue().bytes;i.remove();}}
    private LookupResult copy(LookupResult r,String hint,String original){JsonNode regions=r.regions().deepCopy();for(var node:regions){var region=(ObjectNode)node;region.put("originalSurface",original);region.put("detectedPos",hint==null?"unknown":hint);region.put("posMethod",hint==null?"UNKNOWN":"CALLER_HINT");}if(hint!=null)for(var region:regions){var list=new ArrayList<JsonNode>();region.path("senses").forEach(list::add);list.sort(Comparator.comparing(s->!s.path("pos").asText().equals(hint)));var array=((ObjectNode)region).putArray("senses");list.forEach(array::add);}return new LookupResult(r.status(),r.queryKey(),r.dataset(),r.resolvedLemmaKey(),regions);}
    synchronized int cachedKeys(){return cache.size();}
    synchronized long estimatedCacheBytes(){return cacheBytes;}
}
