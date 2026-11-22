package com.readict.lexical.core;

import static com.readict.lexical.core.DictionaryJson.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/** Standalone validator; has no Spring or database dependency. */
public final class BundleValidator {
    public static final List<String> FILES=List.of("entries.jsonl","senses.jsonl","forms.jsonl","dispositions.jsonl","issues.jsonl","quality-report.json");
    public record Validated(Path root, JsonNode manifest) {}
    private record Entry(long source, String key, int senses) {}
    public static void rows(Path file, Consumer<JsonNode> action) {
        try(var in=new BufferedInputStream(Files.newInputStream(file),65536)) {
            var bytes=new ByteArrayOutputStream(); int c; boolean first=true;
            while((c=in.read())!=-1) {
                if(first) {check(c!=0xef,"BOM_REJECTED");first=false;}
                if(c==10) { check(bytes.size()>0,"EMPTY_LINE"); var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT); String s=decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString();check(!s.contains("\r"),"CR_REJECTED"); JsonNode node;try{node=parse(s);}catch(RuntimeException e){throw new DictionaryFailure("INVALID_JSON_OR_UTF8");} shape(node);action.accept(node);bytes.reset(); }
                else { check(bytes.size()<512*1024,"LINE_BUDGET");bytes.write(c); }
            }
            check(bytes.size()==0,"FINAL_LF_REQUIRED");
        } catch(RuntimeException e) {throw e;} catch(Exception e) {throw new DictionaryFailure("INVALID_JSON_OR_UTF8");}
    }
    private static void shape(JsonNode n){
        if(n.isNumber())check(n.isIntegralNumber()&&n.canConvertToLong()&&n.asLong()>=0&&n.asLong()<=9007199254740991L,"INTEGER_INVALID");
        if(n.isObject())for(String key:n.propertyNames()){
            var child=n.path(key);if(key.equals("sourceRange")&&!child.isNull()) {fields(child,"startOffset","endOffset","unit");long a=number(child,"startOffset"),b=number(child,"endOffset");check(child.path("unit").asText().equals("UTF16_CODE_UNIT")&&b>=a&&b<=2*1024*1024,"SOURCE_RANGE_INVALID");}shape(child);
        }else if(n.isArray())for(var child:n)shape(child);
    }
    private static JsonNode single(Path p) { List<JsonNode> r=new ArrayList<>();rows(p,r::add);check(r.size()==1,"SINGLE_OBJECT_REQUIRED");return r.getFirst(); }
    private static Path safe(Path root,String name) throws IOException {
        check(FILES.contains(name)||name.equals("manifest.json"),"PATH_INVALID"); Path p=root.resolve(name);check(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(p)&&p.toRealPath().equals(p),"PATH_INVALID");return p;
    }
    private static void fields(JsonNode n,String...allowed) {var set=Set.of(allowed);for(String k:n.propertyNames())check(set.contains(k),"UNKNOWN_FIELD");}
    private static boolean flag(JsonNode node,String name,boolean expected){return node.path(name).isBoolean()&&node.path(name).asBoolean()==expected;}
    private static String text(JsonNode n,String k,int max) {var v=n.path(k);check(v.isString(),"FIELD_INVALID");String s=v.asText();check(s.codePointCount(0,s.length())<=max,"FIELD_TOO_LONG");return s;}
    private static long number(JsonNode n,String k) {var v=n.path(k);check(v.isIntegralNumber()&&v.canConvertToLong()&&v.asLong()>=0,"INTEGER_INVALID");return v.asLong();}
    private static String digestFile(Path p) throws Exception {MessageDigest d=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(p)){byte[]b=new byte[65536];int n;while((n=in.read(b))>0)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}
    public Validated validate(Path input,boolean allow) {
        try {
            Path root=input.toAbsolutePath().normalize().toRealPath();check(!root.getFileName().toString().endsWith(".partial")&&!Files.exists(root.resolve(".partial")),"PARTIAL_BUNDLE");
            JsonNode m=single(safe(root,"manifest.json"));fields(m,"schemaVersion","diagnosticOnly","usageProfile","adoptionPolicy","source","datasetVersion","converterVersion","parserVersion","normalizationVersion","rulesVersion","identityHash","datasetId","counts","files","contentHash","quality","rights");
            check(m.path("schemaVersion").asText().equals("readict-col-bundle-2")&&m.path("diagnosticOnly").isBoolean()&&!m.path("diagnosticOnly").asBoolean(),"SCHEMA_OR_DIAGNOSTIC_INVALID");
            check(allow&&m.path("usageProfile").asText().equals("LOCAL_MVP")&&m.path("adoptionPolicy").asText().equals("col-local-mvp-v1"),"LOCAL_MVP_OPT_IN_REQUIRED");
            check(m.path("normalizationVersion").asText().equals("col-normalize-v1")&&m.path("rulesVersion").asText().equals("col-extract-r4"),"VERSION_UNSUPPORTED");
            var src=m.path("source");String mdx=text(src,"mdxSha256",64);check(mdx.matches("[a-f0-9]{64}"),"SOURCE_HASH"); Object mdd=src.path("mddSha256").isNull()?null:text(src,"mddSha256",64);check(mdd==null||mdd.toString().matches("[a-f0-9]{64}"),"SOURCE_HASH");
            check(hash(Arrays.asList("readict-col-source-1",mdx,mdd)).equals(src.path("sourceHash").asText()),"SOURCE_HASH");
            var tuple=List.of(m.path("schemaVersion").asText(),src.path("sourceHash").asText(),text(m,"datasetVersion",64),text(m,"converterVersion",64),text(m,"parserVersion",64),text(m,"normalizationVersion",64),text(m,"rulesVersion",64));
            String identity=hash(tuple); check(identity.equals(m.path("identityHash").asText())&&uuid(identity).toString().equals(m.path("datasetId").asText()),"IDENTITY_MISMATCH");
            List<Object> content=new ArrayList<>();check(m.path("files").size()==FILES.size(),"FILE_WHITELIST");long total=0;
            for(int i=0;i<FILES.size();i++) {var f=m.path("files").get(i);String name=f.path("name").asText();check(name.equals(FILES.get(i)),"FILE_WHITELIST");Path p=safe(root,name);long size=Files.size(p);total+=size;check(size<=1024L*1024*1024&&total<=1024L*1024*1024,"BUNDLE_BUDGET");check(number(f,"sizeBytes")==size&&digestFile(p).equals(f.path("sha256").asText()),"FILE_HASH_MISMATCH");long[] lines={0};rows(p,n->lines[0]++);check(lines[0]==number(f,"lineCount"),"LINE_COUNT");content.add(List.of(name,size,f.path("sha256").asText()));}
            check(hash(content).equals(m.path("contentHash").asText())&&m.path("quality").path("reportSha256").asText().equals(m.path("files").get(5).path("sha256").asText()),"CONTENT_HASH");
            Map<String,Entry> entries=new HashMap<>();Map<Long,long[]> bySource=new HashMap<>();Map<Long,String[]> sourceMeta=new HashMap<>();Set<String> entryKeys=new HashSet<>();
            rows(root.resolve(FILES.get(0)),e->{
                fields(e,"entryId","entryKey","sourceOrdinal","indexKey","sectionOrder","componentOrder","parentEntryKey","headword","lemmaKey","lemmaMethod","phonetics","sourceRecordHash","sourceRange","notes","references");
                long source=number(e,"sourceOrdinal"),section=number(e,"sectionOrder"),component=number(e,"componentOrder");check(section<=Integer.MAX_VALUE&&component<=Integer.MAX_VALUE,"INTEGER_INVALID");String key=text(e,"entryKey",80),id=text(e,"entryId",36),head=text(e,"headword",128);text(e,"indexKey",1024);
                check(id.equals(identity(List.of("entry-v2",m.path("datasetId").asText(),source,section,component,e.path("indexKey").asText())).toString())&&key.equals("s"+source+":r"+section+":c"+component)&&entryKeys.add(key)&&!entries.containsKey(id),"ENTRY_IDENTITY");
                check(normalize(head).equals(e.path("lemmaKey").asText())&&e.path("lemmaMethod").asText().equals("EXACT_HEADWORD")&&e.path("sourceRecordHash").asText().matches("[a-f0-9]{64}")&&e.path("phonetics").isArray()&&e.path("notes").isArray()&&e.path("references").isArray(),"ENTRY_INVALID");
                String[] meta={e.path("indexKey").asText(),e.path("sourceRecordHash").asText()};check(!sourceMeta.containsKey(source)||Arrays.equals(sourceMeta.get(source),meta),"SOURCE_METADATA_MISMATCH");sourceMeta.put(source,meta);entries.put(id,new Entry(source,key,0));bySource.computeIfAbsent(source,x->new long[2])[0]++;
            });
            Set<String> senseIds=new HashSet<>();long[] senses={0};
            rows(root.resolve(FILES.get(1)),s->{fields(s,"senseId","entryId","senseKey","pos","rawPos","zh","providerOrder","sourceLabel","labels","sourceRange","boundaryEvidence");String id=text(s,"entryId",36);Entry e=entries.get(id);check(e!=null,"SENSE_REFERENCE");long order=number(s,"providerOrder");String sid=text(s,"senseId",36);
                check(order==e.senses&&s.path("senseKey").asText().equals("p"+order)&&identity(List.of("sense-v1",id,order)).toString().equals(sid)&&senseIds.add(sid),"SENSE_IDENTITY");check(!text(s,"zh",10000).isBlank()&&Set.of("noun","verb","adjective","adverb","pronoun","preposition","conjunction","determiner","interjection","unknown").contains(text(s,"pos",32))&&s.path("labels").isArray(),"SENSE_INVALID");
                entries.put(id,new Entry(e.source,e.key,e.senses+1));bySource.get(e.source)[1]++;senses[0]++;
            });check(!entries.isEmpty()&&entries.values().stream().allMatch(e->e.senses>0),"EMPTY_ENTRY");
            Map<Long,Long> formOrigins=new HashMap<>();Set<String> formKeys=new HashSet<>();long[] forms={0};
            rows(root.resolve(FILES.get(2)),f->{fields(f,"surfaceKey","entryId","relation","evidence","priority");String surface=text(f,"surfaceKey",128);check(surface.equals(normalize(surface))&&entries.containsKey(f.path("entryId").asText())&&Set.of("INFLECTION","REDIRECT").contains(f.path("relation").asText())&&f.path("evidence").isArray()&&!f.path("evidence").isEmpty()&&formKeys.add(json(List.of(surface,f.path("entryId").asText(),f.path("relation").asText()))),"FORM_INVALID");JsonNode previous=null;for(var evidence:f.path("evidence")){long origin=number(evidence,"sourceOrdinal");check(Set.of("MDX_REDIRECT","COL_EXPLICIT_REFERENCE","COL_EXPLICIT_ENGLISH_FORM","COL_EXPLICIT_FORM_TABLE").contains(evidence.path("ruleId").asText())&&(evidence.path("region").isNull()||evidence.path("region").isIntegralNumber()),"EVIDENCE_INVALID");formOrigins.merge(origin,1L,Long::sum);if(previous!=null){long a=number(previous,"sourceOrdinal"),b=number(evidence,"sourceOrdinal");check(a<=b,"EVIDENCE_ORDER");if(a==b){long ar=previous.path("region").isNull()?-1:previous.path("region").asLong(),br=evidence.path("region").isNull()?-1:evidence.path("region").asLong();check(ar<=br,"EVIDENCE_ORDER");if(ar==br)check(previous.path("ruleId").asText().compareTo(evidence.path("ruleId").asText())<=0,"EVIDENCE_ORDER");}}previous=evidence;}check(number(f,"priority")== (f.path("relation").asText().equals("INFLECTION")?10:20),"PRIORITY_INVALID");forms[0]++;});
            long[] counts=new long[5];Set<Long> failed=new HashSet<>();
            rows(root.resolve(FILES.get(3)),d->{fields(d,"sourceOrdinal","indexKey","sourceRecordHash","outcome","entryCount","senseCount","formCount","issueCodes");long ordinal=number(d,"sourceOrdinal");check(ordinal==counts[0]++,"DISPOSITION_COVERAGE");long generated=number(d,"formCount");check(generated==formOrigins.getOrDefault(ordinal,0L)&&(Set.of("ENTRY","ALIAS").contains(d.path("outcome").asText())||generated==0),"FORM_SOURCE_COUNT");formOrigins.remove(ordinal);counts[4]+=generated;if(sourceMeta.containsKey(ordinal))check(Arrays.equals(sourceMeta.get(ordinal),new String[]{d.path("indexKey").asText(),d.path("sourceRecordHash").asText()})&&d.path("outcome").asText().equals("ENTRY"),"SOURCE_METADATA_MISMATCH");long[]c=bySource.getOrDefault(ordinal,new long[2]);check(c[0]==number(d,"entryCount")&&c[1]==number(d,"senseCount"),"DISPOSITION_COUNTS");String outcome=d.path("outcome").asText();switch(outcome){case "ENTRY","ALIAS"->counts[1]++;case "SKIP"->{counts[2]++;check(c[0]==0,"SKIP_INVALID");}case "FAIL"->{counts[3]++;failed.add(ordinal);check(c[0]==0&&d.path("issueCodes").isArray()&&!d.path("issueCodes").isEmpty(),"QUARANTINE_INVALID");}default->throw new DictionaryFailure("DISPOSITION_INVALID");}});
            check(formOrigins.isEmpty()&&bySource.keySet().stream().allMatch(n->n<counts[0]),"SOURCE_COVERAGE");Set<Long> errors=new HashSet<>();
            rows(root.resolve(FILES.get(4)),i->{long o=number(i,"sourceOrdinal");check(o<counts[0]&&Set.of("ERROR","WARNING").contains(i.path("severity").asText()),"ISSUE_INVALID");if(i.path("severity").asText().equals("ERROR")){check(failed.contains(o),"INCLUDED_BLOCKING_ERROR");errors.add(o);}});check(errors.containsAll(failed),"FAIL_WITHOUT_ERROR");
            String[] cn={"sourceTotal","sourceSucceeded","sourceSkipped","sourceFailed"};var mc=m.path("counts");for(int i=0;i<4;i++)check(number(mc,cn[i])==counts[i],"COUNT_MISMATCH");check(number(mc,"entryCount")==entries.size()&&number(mc,"senseCount")==senses[0]&&number(mc,"formCount")==forms[0]&&number(mc,"formGenerated")==counts[4]&&number(mc,"formGenerated")-number(mc,"formDeduplicated")==forms[0],"COUNT_MISMATCH");
            var q=single(root.resolve(FILES.get(5)));check(q.path("counts").equals(mc)&&q.path("schemaVersion").asText().equals("readict-col-quality-2")&&q.path("usageProfile").asText().equals("LOCAL_MVP")&&q.path("adoptionPolicy").asText().equals("col-local-mvp-v1")&&flag(q,"localMvpEligible",true)&&flag(q,"releaseEligible",false)&&flag(m.path("quality"),"localMvpEligible",true)&&flag(m.path("quality"),"releaseEligible",false),"QUALITY_INVALID");
            var rights=m.path("rights");check(rights.path("status").asText().equals("UNVERIFIED")&&flag(rights,"snapshotAllowed",false)&&flag(rights,"distributionAllowed",false),"RIGHTS_INVALID");
            return new Validated(root,m);
        }catch(DictionaryFailure e){throw e;}catch(Exception e){throw new DictionaryFailure("BUNDLE_INVALID");}
    }
    /** Freeze first, validate the frozen bytes, import only these bytes. */
    public Frozen freeze(Path input,boolean allow) {
        Path dir=null;try {Path root=input.toAbsolutePath().normalize().toRealPath();check(!root.getFileName().toString().endsWith(".partial")&&!Files.exists(root.resolve(".partial")),"PARTIAL_BUNDLE");dir=Files.createTempDirectory("readict-dictionary-");List<String> all=new ArrayList<>(FILES);all.add("manifest.json");long total=0;for(String n:all){Path p=safe(root,n);total+=Files.size(p);check(total<=1024L*1024*1024+512*1024,"BUNDLE_BUDGET");Files.copy(p,dir.resolve(n));}return new Frozen(validate(dir,allow));}catch(RuntimeException|IOException e){if(dir!=null)remove(dir);if(e instanceof DictionaryFailure d)throw d;throw new DictionaryFailure("BUNDLE_FREEZE_FAILED");}
    }
    public record Frozen(Validated bundle) implements AutoCloseable {public void close(){remove(bundle.root);}}
    private static void remove(Path root){try(var walk=Files.walk(root)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}catch(IOException e){throw new DictionaryFailure("STAGING_CLEANUP_FAILED");}}
}
