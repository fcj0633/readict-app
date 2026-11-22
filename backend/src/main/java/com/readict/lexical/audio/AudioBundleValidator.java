package com.readict.lexical.audio;

import static com.readict.lexical.core.DictionaryJson.*;
import static com.readict.lexical.audio.AudioFiles.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class AudioBundleValidator {
    public static final List<String> FILES=List.of("assets.jsonl","mappings.jsonl","issues.jsonl","quality.json","dispositions.jsonl");
    public static final Set<String> QUARANTINABLE=Set.of("AUDIO_SOURCE_SIGNAL","AUDIO_OUTPUT_QUALITY","AUDIO_SOURCE_FORMAT","AUDIO_SOURCE_DECODE","AUDIO_PCM_INVALID");
    public record Bundle(Path root,JsonNode manifest,Path mediaRoot) {
        public Bundle(Path root,JsonNode manifest){this(root,manifest,root);}
    }
    public record Frozen(Bundle bundle) implements AutoCloseable { public void close() throws IOException{deleteOwnedTree(bundle.root());} }
    public Bundle validate(Path supplied) throws IOException {
        return validate(supplied,false);
    }
    public Bundle validate(Path supplied,boolean allowSubset) throws IOException {
        return validate(supplied,allowSubset,true);
    }
    private Bundle validate(Path supplied,boolean allowSubset,boolean checkMedia) throws IOException {
        Path root=supplied.toRealPath();check(!root.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".partial")&&!Files.exists(root.resolve("state.json")),"AUDIO_PARTIAL");
        JsonNode m=object(safe(root,"manifest.json"));
        check(m.path("schemaVersion").asText().equals("readict-audio-bundle-1")&&m.path("diagnosticOnly").isBoolean()&&!m.path("diagnosticOnly").asBoolean()&&m.path("normalizeVersion").asText().equals("col-normalize-v1"),"AUDIO_SCHEMA_INVALID");
        check(m.path("sources").isArray()&&m.path("sources").size()==3,"AUDIO_SOURCE_INVALID");int sourceIndex=0;
        for(var source:m.path("sources")){check(source.path("role").asText().equals(List.of("icons","mdd","mdx").get(sourceIndex++))&&number(source,"sizeBytes")>0,"AUDIO_SOURCE_INVALID");hex(source,"sha256");}
        field(m,"converterVersion",100);field(m,"parserVersion",100);var encoding=m.path("encoding");
        check(encoding.isObject()&&encoding.size()==5&&number(encoding,"sampleRate")==22050&&number(encoding,"channels")==1&&number(encoding,"bitRate")==48000&&encoding.path("codec").asText().equals("libmp3lame")&&encoding.path("metadata").asText().equals("removed"),"AUDIO_ENCODING_INVALID");
        for(var tool:List.of(m.path("ffmpeg"),m.path("ffprobe"))){hex(tool,"sha256");check(number(tool,"sizeBytes")>0,"AUDIO_TOOL_INVALID");}
        boolean subset=m.path("usageProfile").asText().equals("LOCAL_MVP");
        check(subset?allowSubset&&m.path("adoptionPolicy").asText().equals("audio-local-subset-v1")&&m.path("approvalRef").asText().equals("user-2026-11-22-allow-defective-recordings")&&m.path("converterVersion").asText().endsWith("/local-subset-v1"):m.path("usageProfile").asText().equals("FULL")&&m.path("adoptionPolicy").asText().equals("audio-full-v1")&&m.has("approvalRef")&&m.path("approvalRef").isNull()&&m.path("converterVersion").asText().endsWith("/full-v1"),"AUDIO_POLICY_NOT_ALLOWED");
        JsonNode tuple=m.path("identityTuple");
        var sourceTuple=new ArrayList<Object>();for(var source:m.path("sources"))sourceTuple.add(List.of(source.path("role").asText(),number(source,"sizeBytes"),source.path("sha256").asText()));
        check(tuple.isArray()&&tuple.size()==7&&tuple.get(0).asText().equals("audio-dataset-v1")&&tuple.get(1).asText().equals(hash(sourceTuple))&&tuple.get(2).asText().equals(m.path("converterVersion").asText())&&tuple.get(3).asText().equals(m.path("parserVersion").asText())&&tuple.get(4).asText().equals(m.path("ffmpeg").path("sha256").asText())&&tuple.get(5).asText().equals(hash(m.path("encoding")))&&tuple.get(6).asText().equals("col-normalize-v1"),"AUDIO_IDENTITY_INVALID");
        check(hash(tuple).equals(m.path("identitySha256").asText())&&identity(tuple).toString().equals(m.path("datasetId").asText())&&m.path("version").asText().equals("audio-v1/"+m.path("datasetId").asText()),"AUDIO_IDENTITY_INVALID");
        check(m.path("files").size()==FILES.size(),"AUDIO_FILES_INVALID");
        for(int i=0;i<FILES.size();i++){var f=m.path("files").get(i);check(f.path("name").asText().equals(FILES.get(i)),"AUDIO_FILES_INVALID");Path p=safe(root,FILES.get(i));check(Files.isRegularFile(p)&&Files.size(p)==number(f,"sizeBytes")&&digestFile(p).equals(f.path("sha256").asText()),"AUDIO_DIGEST_INVALID");}
        var resources=m.path("resourceCounts");check(resources.isObject()&&resources.size()==2&&resources.has("mdd")&&resources.has("icons")&&m.path("nonAudioResources").isArray(),"AUDIO_RESOURCE_COVERAGE");
        long mdd=number(resources,"mdd"),icons=number(resources,"icons");Set<String> covered=new HashSet<>();
        for(var r:m.path("nonAudioResources")){String role=r.path("role").asText();long ordinal=number(r,"sourceOrdinal");field(r,"sourceResourceKey",512);check(Set.of("mdd","icons").contains(role)&&r.path("kind").asText().equals("IMAGE")&&ordinal<number(resources,role)&&covered.add(role+":"+ordinal),"AUDIO_RESOURCE_COVERAGE");}
        Set<String> ids=new HashSet<>(),expectedFiles=new HashSet<>(FILES);expectedFiles.add("manifest.json");Set<Long> ordinals=new HashSet<>(),quarantined=new HashSet<>();long[] totals={0,0,0,0,0};long[] previous={-1};
        Map<Long,Long> mappingSources=new HashMap<>(),excludedSources=new HashMap<>();
        try(var io=new AudioIo()) { rows(root.resolve(FILES.get(0)),a->{
            field(a,"sourceResourceKey",512);hex(a,"sourceSha256");hex(a,"outputSha256");
            String sha=a.path("outputSha256").asText(),key=a.path("storageKey").asText(),id=a.path("audioId").asText();long ordinal=number(a,"sourceOrdinal");
            check(key.equals("media/"+sha.substring(0,2)+"/"+sha+".mp3")&&a.path("mime").asText().equals("audio/mpeg")&&number(a,"sampleRate")==22050&&number(a,"channels")==1&&number(a,"durationMs")>0&&number(a,"durationMs")<=60080&&number(a,"bytes")>0&&number(a,"bytes")<=2097152,"AUDIO_ASSET_INVALID");
            check(identity(List.of("audio-asset-v1",m.path("datasetId").asText(),a.path("sourceResourceKey").asText(),a.path("sourceSha256").asText(),sha)).toString().equals(id)&&ids.add(id)&&ordinals.add(ordinal)&&ordinal>previous[0]&&ordinal<mdd&&covered.add("mdd:"+ordinal),"AUDIO_ASSET_IDENTITY");previous[0]=ordinal;
            if(checkMedia)try{io.submit(()->{try{Path p=safe(root,key);check(Files.size(p)==a.path("bytes").asLong()&&digestFile(p).equals(sha),"AUDIO_MEDIA_INVALID");}catch(IOException e){throw new DictionaryFailure("AUDIO_MEDIA_INVALID");}});}catch(IOException e){throw new DictionaryFailure("AUDIO_MEDIA_INVALID");}totals[0]++;
            expectedFiles.add(key);
        }); }
        Set<String> keys=new HashSet<>();var pending=new ArrayList<JsonNode>();
        rows(root.resolve(FILES.get(2)),r->{
            field(r,"code",128);check(Set.of("ERROR","WARNING").contains(r.path("severity").asText()),"AUDIO_ISSUE_INVALID");
            if(r.path("stage").asText().equals("CONVERSION")){
                long ordinal=number(r,"sourceOrdinal");field(r,"resourceKey",512);hex(r,"sourceSha256");long size=number(r,"sizeBytes");
                check(subset&&r.path("severity").asText().equals("ERROR")&&r.path("disposition").asText().equals("QUARANTINED")&&QUARANTINABLE.contains(r.path("code").asText())&&ordinal<mdd&&size>0&&size<=2097152&&covered.add("mdd:"+ordinal)&&quarantined.add(ordinal),"AUDIO_UNAPPROVED_FAILURE");if(r.path("code").asText().equals("AUDIO_SOURCE_DECODE"))check(r.path("evidence").path("ruleId").asText().equals("ffmpeg-strict-speex-v1")&&r.path("evidence").path("decoder").asText().equals("speex")&&r.path("evidence").path("result").asText().equals("INVALID_PACKET"),"AUDIO_DECODE_EVIDENCE");totals[3]++;
            }else if(r.path("stage").asText().equals("MAPPING")&&r.path("code").asText().equals("AUDIO_RESOURCE_QUARANTINED"))pending.add(r);
            else if(r.path("severity").asText().equals("ERROR"))totals[2]++;
        });
        for(var r:pending){long ordinal=number(r,"sourceOrdinal"),link=number(r,"linkOrder");check(r.path("severity").asText().equals("WARNING")&&r.path("disposition").asText().equals("QUARANTINED")&&quarantined.contains(number(r,"resourceOrdinal"))&&keys.add(ordinal+":"+link),"AUDIO_QUARANTINE_REFERENCE");excludedSources.merge(ordinal,1L,Long::sum);totals[4]++;}
        long[] order={-1,-1};
        rows(root.resolve(FILES.get(1)),r->{
            long ordinal=number(r,"sourceOrdinal"),link=number(r,"linkOrder");field(r,"indexKey",255);field(r,"displayHeadword",255);field(r,"wordKey",255);
            check(ids.contains(r.path("audioId").asText())&&keys.add(ordinal+":"+link)&&r.path("normalizeVersion").asText().equals("col-normalize-v1")&&normalize(r.path("displayHeadword").asText()).equals(r.path("wordKey").asText())&&Set.of("en-GB","en-US").contains(r.path("accent").asText())&&r.path("evidence").path("href").asText().startsWith("sound://"),"AUDIO_MAPPING_INVALID");
            check(ordinal>order[0]||ordinal==order[0]&&link>order[1],"AUDIO_MAPPING_ORDER");order[0]=ordinal;order[1]=link;totals[1]++;mappingSources.merge(ordinal,1L,Long::sum);
        });
        check(covered.size()==mdd+icons,"AUDIO_RESOURCE_COVERAGE");
        long[] sources={0,0,0,0,0},links={0,0,0,0};
        rows(root.resolve("dispositions.jsonl"),d->{
            long ordinal=number(d,"sourceOrdinal"),resolved=number(d,"resolvedCount"),missing=number(d,"missingCount"),invalid=number(d,"invalidCount"),out=number(d,"outOfScopeCount");
            check(ordinal==sources[0]++&&resolved==mappingSources.getOrDefault(ordinal,0L)+excludedSources.getOrDefault(ordinal,0L)&&number(d,"linkCount")==resolved+missing+invalid+out,"AUDIO_SOURCE_COVERAGE");
            int outcome=switch(d.path("outcome").asText()){case "MAPPED"->1;case "OUT_OF_SCOPE"->2;case "NO_AUDIO"->3;case "FAIL"->4;default->throw new DictionaryFailure("AUDIO_SOURCE_COVERAGE");};sources[outcome]++;links[0]+=resolved;links[1]+=missing;links[2]+=invalid;links[3]+=out;
        });
        JsonNode q=object(root.resolve(FILES.get(3))),c=m.path("counts");
        check(sources[0]==number(c,"mdxTotal")&&sources[1]==number(c,"sourceMapped")&&sources[2]==number(c,"sourceOutOfScope")&&sources[3]==number(c,"sourceNoAudio")&&sources[4]==number(c,"sourceFailed")&&links[0]==number(c,"linksResolved")&&links[1]==number(c,"linksMissing")&&links[2]==number(c,"linksInvalid")&&links[3]==number(c,"linksOutOfScope")&&covered.size()==number(c,"mddTotal")&&m.path("nonAudioResources").size()==number(c,"nonAudio"),"AUDIO_SOURCE_COVERAGE");
        check(q.path("schemaVersion").asText().equals("readict-audio-quality-1")&&q.path("humanReview").asText().equals("NOT_PERFORMED")&&q.path("rightsReview").asText().equals("UNVERIFIED")&&q.path("counts").equals(c)&&q.path("technicalEligible").isBoolean()&&q.path("technicalEligible").asBoolean()&&m.path("technicalEligible").isBoolean()&&m.path("technicalEligible").asBoolean()&&m.path("releaseEligible").isBoolean()&&!m.path("releaseEligible").asBoolean()&&q.path("releaseEligible").isBoolean()&&!q.path("releaseEligible").asBoolean()&&m.path("rights").path("status").asText().equals("UNVERIFIED")&&m.path("rights").path("snapshotAllowed").isBoolean()&&!m.path("rights").path("snapshotAllowed").asBoolean()&&m.path("rights").path("distributionAllowed").isBoolean()&&!m.path("rights").path("distributionAllowed").asBoolean(),"AUDIO_QUALITY_INVALID");
        check(totals[0]>0&&totals[0]==number(c,"assetCount")&&totals[1]==number(c,"mappingCount")&&totals[1]+totals[4]==number(c,"linksResolved")&&totals[2]==0&&number(c,"audioCandidates")==totals[0]+totals[3]&&number(c,"conversionFailed")==totals[3]&&number(c,"quarantinedCount")==quarantined.size()&&number(c,"mappingQuarantined")==totals[4]&&number(c,"blockingErrors")==0&&number(c,"mappingErrors")==0&&number(c,"unknownResources")==0&&number(c,"sourceFailed")==0&&number(c,"linksMissing")==0&&number(c,"linksInvalid")==0&&number(c,"mddTotal")==number(c,"audioCandidates")+number(c,"nonAudio")&&m.path("completeSourceEligible").isBoolean()&&m.path("completeSourceEligible").asBoolean()==(totals[3]==0),"AUDIO_QUALITY_REJECTED");
        Files.walkFileTree(root,new SimpleFileVisitor<Path>(){
            @Override public FileVisitResult preVisitDirectory(Path dir,java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                String relative=root.relativize(dir).toString().replace('\\','/');
                check(!attrs.isSymbolicLink()&&dir.toRealPath().startsWith(root),"AUDIO_PATH_ESCAPE");
                check(relative.isEmpty()||relative.equals("media")||relative.matches("media/[0-9a-f]{2}"),"AUDIO_UNEXPECTED_FILE");return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs){
                check(!attrs.isSymbolicLink()&&attrs.isRegularFile(),"AUDIO_PATH_ESCAPE");check(expectedFiles.contains(root.relativize(file).toString().replace('\\','/')),"AUDIO_UNEXPECTED_FILE");return FileVisitResult.CONTINUE;
            }
        });
        return new Bundle(root,m);
    }
    public Frozen freeze(Path supplied) throws IOException {
        return freeze(supplied,false);
    }
    /** Pin verified metadata; each source media is checked when atomically published privately. */
    public Frozen freezeForImport(Path supplied,boolean allowSubset) throws IOException {
        Bundle b=validate(supplied,allowSubset,false);Path tmp=Files.createTempDirectory("readict-audio-import-");
        try{
            Files.copy(safe(b.root(),"manifest.json"),tmp.resolve("manifest.json"));
            for(String file:FILES)Files.copy(safe(b.root(),file),tmp.resolve(file));
            check(object(tmp.resolve("manifest.json")).equals(b.manifest()),"AUDIO_SOURCE_CHANGED");
            for(var file:b.manifest().path("files"))check(Files.size(tmp.resolve(file.path("name").asText()))==number(file,"sizeBytes")&&digestFile(tmp.resolve(file.path("name").asText())).equals(file.path("sha256").asText()),"AUDIO_SOURCE_CHANGED");
            return new Frozen(new Bundle(tmp,b.manifest(),b.root()));
        }catch(Exception e){deleteOwnedTree(tmp);throw e;}
    }
    public Frozen freeze(Path supplied,boolean allowSubset) throws IOException {
        Bundle b=validate(supplied,allowSubset);Path tmp=Files.createTempDirectory("readict-audio-frozen-");
        try{
            Files.copy(safe(b.root(),"manifest.json"),tmp.resolve("manifest.json"));for(String file:FILES)Files.copy(safe(b.root(),file),tmp.resolve(file));
            check(object(tmp.resolve("manifest.json")).equals(b.manifest()),"AUDIO_SOURCE_CHANGED");
            for(var file:b.manifest().path("files"))check(Files.size(tmp.resolve(file.path("name").asText()))==number(file,"sizeBytes")&&digestFile(tmp.resolve(file.path("name").asText())).equals(file.path("sha256").asText()),"AUDIO_SOURCE_CHANGED");
            // Only copied, hash-verified metadata controls the frozen media list.
            var copied=new HashSet<String>();Path owned=tmp.toRealPath();
            try(var io=new AudioIo()){rows(tmp.resolve("assets.jsonl"),a->{try{
                String key=a.path("storageKey").asText();if(!copied.add(key))return;
                io.submit(()->{Path source=safe(b.root(),key),target=owned.resolve(key).normalize();
                    check(target.startsWith(owned),"AUDIO_PATH_ESCAPE");Files.createDirectories(target.getParent());Files.copy(source,target);});
            }catch(IOException e){throw new DictionaryFailure("AUDIO_FREEZE_FAILED");}});}
            Bundle frozen=validate(tmp,allowSubset);check(frozen.manifest().equals(b.manifest()),"AUDIO_SOURCE_CHANGED");return new Frozen(frozen);
        }catch(Exception e){deleteOwnedTree(tmp);throw e;}
    }
    static void field(JsonNode n,String k,int max){check(n.path(k).isString()&&!n.path(k).asText().isEmpty()&&n.path(k).asText().codePointCount(0,n.path(k).asText().length())<=max,"AUDIO_FIELD_INVALID");}
    static void hex(JsonNode n,String k){check(n.path(k).asText().matches("[0-9a-f]{64}"),"AUDIO_DIGEST_INVALID");}
    static long number(JsonNode n,String k){check(n.path(k).isIntegralNumber()&&n.path(k).canConvertToLong()&&n.path(k).asLong()>=0&&n.path(k).asLong()<=9007199254740991L,"AUDIO_INTEGER_INVALID");return n.path(k).asLong();}
}
