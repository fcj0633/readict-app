package com.readict.lexical.audio;

import static com.readict.lexical.core.DictionaryJson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.node.*;

final class AudioFixtures {
    static Path create(Path root,int count) throws Exception {return create(root,count,null);}
    static Path create(Path root,int count,byte[] firstMedia) throws Exception {
        Files.createDirectories(root);ObjectNode m=(ObjectNode)AudioFiles.object(Path.of("src/test/resources/audio/synthetic-bundle/manifest.json"));
        String converter="synthetic-"+UUID.randomUUID()+"/full-v1";((ArrayNode)m.path("identityTuple")).set(2,JSON.getNodeFactory().stringNode(converter));m.put("converterVersion",converter);
        String dataset=identity(m.path("identityTuple")).toString();m.put("identitySha256",hash(m.path("identityTuple")));m.put("datasetId",dataset);m.put("version","audio-v1/"+dataset);
        var assets=new ArrayList<String>();var mappings=new ArrayList<String>();var dispositions=new ArrayList<String>();
        for(int i=0;i<count;i++){
            ObjectNode a=(ObjectNode)AudioFiles.object(Path.of("src/test/resources/audio/synthetic-bundle/assets.jsonl"));
            byte[] media=i==0&&firstMedia!=null?firstMedia:("self-authored-test-media-"+i).getBytes(StandardCharsets.UTF_8);String sha=digest(media),source="\\sample"+i+".spx",key="media/"+sha.substring(0,2)+"/"+sha+".mp3";
            String id=identity(List.of("audio-asset-v1",dataset,source,a.path("sourceSha256").asText(),sha)).toString();
            a.put("audioId",id);a.put("sourceOrdinal",i);a.put("sourceResourceKey",source);a.put("outputSha256",sha);a.put("storageKey",key);a.put("bytes",media.length);
            Path file=root.resolve(key);Files.createDirectories(file.getParent());Files.write(file,media);assets.add(json(a));
            ObjectNode mapping=(ObjectNode)AudioFiles.object(Path.of("src/test/resources/audio/synthetic-bundle/mappings.jsonl"));mapping.put("audioId",id);mapping.put("sourceOrdinal",i);mappings.add(json(mapping));dispositions.add(json(Map.of("sourceOrdinal",i,"outcome","MAPPED","linkCount",1,"resolvedCount",1,"missingCount",0,"invalidCount",0,"outOfScopeCount",0)));
        }
        Files.writeString(root.resolve("assets.jsonl"),String.join("\n",assets)+"\n");Files.writeString(root.resolve("mappings.jsonl"),String.join("\n",mappings)+"\n");Files.writeString(root.resolve("issues.jsonl"),"");
        Files.writeString(root.resolve("dispositions.jsonl"),String.join("\n",dispositions)+"\n");((ObjectNode)m.path("resourceCounts")).put("mdd",count);
        ObjectNode c=(ObjectNode)m.path("counts");for(String key:List.of("mdxTotal","mddTotal","audioCandidates","sourceMapped","linksTotal","linksResolved","assetCount","mappingCount"))c.put(key,count);
        ObjectNode q=(ObjectNode)AudioFiles.object(Path.of("src/test/resources/audio/synthetic-bundle/quality.json"));q.set("counts",c.deepCopy());Files.writeString(root.resolve("quality.json"),json(q)+"\n");
        ArrayNode files=JSON.createArrayNode();for(String name:AudioBundleValidator.FILES){Path file=root.resolve(name);var entry=files.addObject();entry.put("name",name);entry.put("sizeBytes",Files.size(file));entry.put("sha256",AudioFiles.digestFile(file));}m.set("files",files);Files.writeString(root.resolve("manifest.json"),json(m)+"\n");return root;
    }
}
