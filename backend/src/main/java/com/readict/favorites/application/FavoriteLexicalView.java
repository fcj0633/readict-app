package com.readict.favorites.application;

import java.util.*;
import org.springframework.stereotype.Component;
import com.readict.favorites.infrastructure.persistence.FavoriteRepository;
import com.readict.lexical.core.DictionaryProvider;
import tools.jackson.databind.JsonNode;

/** Builds the current full dictionary view for a saved favorite without exposing provider metadata. */
@Component
public class FavoriteLexicalView {
    public record View(String ipa,List<Map<String,Object>> groups,String definitionStatus,boolean dictionaryChanged) {}

    private final DictionaryProvider dictionary;
    public FavoriteLexicalView(DictionaryProvider dictionary) { this.dictionary=dictionary; }

    public View view(Map<String,Object> row,int limit) {
        String lemmaKey=text(row,"lemma_key"), lemma=text(row,"lemma");
        String savedDatasetVersion=text(row,"dataset_version"), savedNormalization=text(row,"normalization_version");
        String savedEntryKey=text(row,"entry_key"), savedIpa=text(row,"ipa_snapshot");
        try {
            var result=dictionary.lookup(lemma,null);
            if(!Set.of("FOUND","AMBIGUOUS").contains(result.status()) || result.dataset()==null
                    || !Objects.equals(savedNormalization,result.dataset().normalizationVersion())) return snapshot(row,limit,true);
            var regions=new ArrayList<JsonNode>();
            for(JsonNode region:result.regions()) if(lemmaKey.equals(region.path("lemmaKey").asString())) regions.add(region);
            if(regions.isEmpty()) return snapshot(row,limit,true);
            boolean sameDataset=Objects.equals(savedDatasetVersion,result.dataset().datasetVersion());
            int sourceOrdinal=-1;
            if(savedEntryKey!=null) for(JsonNode region:regions)
                if(savedEntryKey.equals(region.path("entryKey").asString())) { sourceOrdinal=region.path("sourceOrdinal").asInt(); break; }
            if(sourceOrdinal<0) {
                if(savedEntryKey!=null && sameDataset) return snapshot(row,limit,true);
                sourceOrdinal=richestSource(regions);
            }
            var selected=new ArrayList<JsonNode>();
            for(JsonNode region:regions) if(region.path("sourceOrdinal").asInt()==sourceOrdinal) selected.add(region);
            selected.sort(Comparator.comparingInt((JsonNode region) -> region.path("sectionOrder").asInt())
                    .thenComparingInt(region -> region.path("componentOrder").asInt()));
            var groups=new ArrayList<Map<String,Object>>();
            var seen=new HashSet<String>();
            for(JsonNode region:selected) {
                var senses=new ArrayList<JsonNode>();
                region.path("senses").forEach(senses::add);
                senses.sort(Comparator.comparingInt(sense -> sense.path("providerOrder").asInt()));
                for(JsonNode sense:senses) {
                    String id=sense.path("senseId").asString();
                    if(id.isBlank() || !seen.add(id)) continue;
                    if(groups.size()>=limit) break;
                    groups.add(map("senseId",id,"pos",sense.path("pos").asString("unknown"),
                            "rawPos",sense.path("rawPos").isString()&&!sense.path("rawPos").asString().isBlank()?sense.path("rawPos").asString():null,
                            "zh",sense.path("zh").asString()));
                }
                if(groups.size()>=limit) break;
            }
            if(groups.isEmpty()) return snapshot(row,limit,true);
            String ipa=savedIpa;
            if(ipa==null) for(JsonNode region:selected) for(JsonNode phonetic:region.path("phonetics"))
                if(phonetic.path("ipa").isString() && !phonetic.path("ipa").asString().isBlank()) { ipa=phonetic.path("ipa").asString(); break; }
            return new View(ipa,List.copyOf(groups),"complete",!sameDataset);
        } catch(RuntimeException error) {
            return snapshot(row,limit,true);
        }
    }

    private static int richestSource(List<JsonNode> regions) {
        Map<Integer,Integer> counts=new HashMap<>();
        for(JsonNode region:regions) counts.merge(region.path("sourceOrdinal").asInt(),region.path("senses").size(),Integer::sum);
        int best=-1,size=-1;
        for(var entry:counts.entrySet()) if(entry.getValue()>size || (entry.getValue()==size && entry.getKey()<best)) {
            best=entry.getKey(); size=entry.getValue();
        }
        return best;
    }

    private static View snapshot(Map<String,Object> row,int limit,boolean changed) {
        String meaning=text(row,"meaning_snapshot");
        var group=map("senseId","snapshot:"+FavoriteRepository.id(row,"id"),"pos",text(row,"pos_snapshot"),"rawPos",null,"zh",meaning);
        List<Map<String,Object>> groups=limit>0?List.of(group):List.of();
        return new View(text(row,"ipa_snapshot"),groups,"snapshot",changed);
    }

    private static String text(Map<String,Object> row,String key) { return FavoriteRepository.text(row,key); }
    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
}
