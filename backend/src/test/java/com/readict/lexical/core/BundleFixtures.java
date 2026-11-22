package com.readict.lexical.core;

import static com.readict.lexical.core.DictionaryJson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

/** Self-authored fixture builder, never reads a real dictionary. */
public final class BundleFixtures {
    private BundleFixtures(){}
    public static Path create(Path root,int size) throws Exception {
        Files.createDirectories(root);Path original=Path.of("src/test/resources/lexical/synthetic-mvp");var m=(ObjectNode)parse(Files.readString(original.resolve("manifest.json")));String version="test-"+UUID.randomUUID();m.put("datasetVersion",version);
        String hash=hash(List.of(m.path("schemaVersion").asText(),m.path("source").path("sourceHash").asText(),version,m.path("converterVersion").asText(),m.path("parserVersion").asText(),m.path("normalizationVersion").asText(),m.path("rulesVersion").asText()));String dataset=uuid(hash).toString();m.put("identityHash",hash);m.put("datasetId",dataset);
        var originalEntry=(ObjectNode)parse(Files.readString(original.resolve("entries.jsonl")));var originalSense=(ObjectNode)parse(Files.readString(original.resolve("senses.jsonl")));StringBuilder entries=new StringBuilder(),senses=new StringBuilder();String first=null;
        for(int i=0;i<size;i++){var e=originalEntry.deepCopy();String id=identity(List.of("entry-v2",dataset,0,i,0,"testword")).toString();if(first==null)first=id;e.put("entryId",id);e.put("entryKey","s0:r"+i+":c0");e.put("sectionOrder",i);entries.append(json(e)).append('\n');var s=originalSense.deepCopy();s.put("entryId",id);s.put("senseId",identity(List.of("sense-v1",id,0)).toString());senses.append(json(s)).append('\n');}
        Files.writeString(root.resolve("entries.jsonl"),entries);Files.writeString(root.resolve("senses.jsonl"),senses);var form=(ObjectNode)parse(Files.readString(original.resolve("forms.jsonl")));form.put("entryId",first);Files.writeString(root.resolve("forms.jsonl"),json(form)+"\n");
        List<JsonNode> dispositions=new ArrayList<>();BundleValidator.rows(original.resolve("dispositions.jsonl"),d->{var row=(ObjectNode)d.deepCopy();if(row.path("sourceOrdinal").asInt()==0){row.put("entryCount",size);row.put("senseCount",size);}dispositions.add(row);});StringBuilder ds=new StringBuilder();dispositions.forEach(d->ds.append(json(d)).append('\n'));Files.writeString(root.resolve("dispositions.jsonl"),ds);Files.copy(original.resolve("issues.jsonl"),root.resolve("issues.jsonl"));
        var counts=(ObjectNode)m.path("counts");counts.put("entryCount",size);counts.put("senseCount",size);var q=(ObjectNode)parse(Files.readString(original.resolve("quality-report.json")));q.set("counts",counts.deepCopy());Files.writeString(root.resolve("quality-report.json"),json(q)+"\n");Files.writeString(root.resolve("manifest.json"),json(m)+"\n");rehash(root);return root;
    }
    public static void rehash(Path root)throws Exception{var m=(ObjectNode)parse(Files.readString(root.resolve("manifest.json")));var files=m.putArray("files");List<Object> tuples=new ArrayList<>();for(String name:BundleValidator.FILES){byte[]b=Files.readAllBytes(root.resolve(name));var f=files.addObject();f.put("name",name);f.put("sizeBytes",b.length);f.put("lineCount",new String(b,StandardCharsets.UTF_8).chars().filter(c->c=='\n').count());String hash=digest(b);f.put("sha256",hash);tuples.add(List.of(name,b.length,hash));}m.put("contentHash",hash(tuples));((ObjectNode)m.path("quality")).put("reportSha256",files.get(5).path("sha256").asText());Files.writeString(root.resolve("manifest.json"),json(m)+"\n");}
}
