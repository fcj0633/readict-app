package com.readict.lexical.audio;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AudioBundleValidatorTest {
    @TempDir Path tmp;
    @Test void nodeGoldenBundleIsValidWithoutSpringOrDatabase() throws Exception {var b=new AudioBundleValidator().validate(Path.of("src/test/resources/audio/synthetic-bundle"));assertThat(b.manifest().path("counts").path("assetCount").asInt()).isEqualTo(1);}
    @Test void subsetGoldenRequiresExplicitOptInAndStillAccountsForQuarantine() throws Exception {Path path=Path.of("src/test/resources/audio/synthetic-subset-bundle");assertThatThrownBy(()->new AudioBundleValidator().validate(path)).hasMessage("AUDIO_POLICY_NOT_ALLOWED");var bundle=new AudioBundleValidator().validate(path,true);assertThat(bundle.manifest().path("counts").path("quarantinedCount").asInt()).isEqualTo(1);}
    @Test void freezeIsIndependentAndIsCleaned() throws Exception {
        Path source=AudioFixtures.create(tmp.resolve("source"),2),frozen;
        try(var f=new AudioBundleValidator().freeze(source)){frozen=f.bundle().root();Files.writeString(source.resolve("manifest.json"),"changed");assertThat(new AudioBundleValidator().validate(frozen).manifest().path("counts").path("assetCount").asInt()).isEqualTo(2);}assertThat(frozen).doesNotExist();
        Path stream=AudioFixtures.create(tmp.resolve("stream-source"),1);
        try(var f=new AudioBundleValidator().freezeForImport(stream,false)){
            frozen=f.bundle().root();var a=AudioFiles.object(frozen.resolve("assets.jsonl"));String key=a.path("storageKey").asText();
            Files.writeString(stream.resolve("manifest.json"),"changed");assertThat(AudioFiles.object(frozen.resolve("manifest.json")).path("counts").path("assetCount").asInt()).isEqualTo(1);
            assertThat(f.bundle().mediaRoot()).isEqualTo(stream.toRealPath());assertThat(frozen.resolve("media")).doesNotExist();
            Files.writeString(stream.resolve(key),"changed");var env=new org.springframework.mock.env.MockEnvironment();env.setActiveProfiles("test");var storage=new AudioStorage(tmp.resolve("readict_test").toString(),env);
            assertThatThrownBy(()->storage.publish(AudioFiles.safe(f.bundle().mediaRoot(),key),key,a.path("outputSha256").asText(),a.path("bytes").asLong())).hasMessage("AUDIO_MEDIA_INVALID");
        }
        assertThat(frozen).doesNotExist();
    }
    @Test void duplicateJsonAndBrokenUtf8AreRejected() throws Exception {Path root=AudioFixtures.create(tmp.resolve("source"),1);String text=Files.readString(root.resolve("manifest.json"));Files.writeString(root.resolve("manifest.json"),text.replace("\"diagnosticOnly\":false","\"diagnosticOnly\":false,\"diagnosticOnly\":false"));assertThatThrownBy(()->new AudioBundleValidator().validate(root)).isInstanceOf(RuntimeException.class);Files.write(root.resolve("manifest.json"),new byte[]{(byte)0xc3,40,10});assertThatThrownBy(()->new AudioBundleValidator().validate(root)).isInstanceOf(java.io.IOException.class);}
    @Test void partialAndTraversalAreRejected() throws Exception {Path root=AudioFixtures.create(tmp.resolve("source"),1);Files.writeString(root.resolve("state.json"),"{}");assertThatThrownBy(()->new AudioBundleValidator().validate(root)).hasMessage("AUDIO_PARTIAL");assertThatThrownBy(()->AudioFiles.safe(root,"../outside")).hasMessage("AUDIO_PATH_INVALID");assertThatThrownBy(()->AudioFiles.safe(root,"C:/outside")).hasMessage("AUDIO_PATH_INVALID");}
    @Test void modifiedMediaAndUnknownSchemaAreRejected() throws Exception {Path root=AudioFixtures.create(tmp.resolve("source"),1);var a=AudioFiles.object(root.resolve("assets.jsonl"));Files.writeString(root.resolve(a.path("storageKey").asText()),"changed");assertThatThrownBy(()->new AudioBundleValidator().validate(root)).hasMessage("AUDIO_MEDIA_INVALID");Files.writeString(root.resolve("manifest.json"),Files.readString(root.resolve("manifest.json")).replace("readict-audio-bundle-1","future-schema"));assertThatThrownBy(()->new AudioBundleValidator().validate(root)).hasMessage("AUDIO_SCHEMA_INVALID");}
    @Test void decodeQuarantineRequiresEvidenceAndNeverAllowsInfrastructureErrors() throws Exception{
        Path root=tmp.resolve("subset"),source=Path.of("src/test/resources/audio/synthetic-subset-bundle");
        try(var walk=Files.walk(source)){for(Path file:(Iterable<Path>)walk::iterator){Path to=root.resolve(source.relativize(file));if(Files.isDirectory(file))Files.createDirectories(to);else Files.copy(file,to);}}
        var issue=(tools.jackson.databind.node.ObjectNode)AudioFiles.object(root.resolve("issues.jsonl"));
        var manifest=AudioFiles.object(root.resolve("manifest.json"));issue.put("code","AUDIO_SOURCE_DECODE");
        rewriteIssue(root,issue,manifest);assertThatThrownBy(()->new AudioBundleValidator().validate(root,true)).hasMessage("AUDIO_DECODE_EVIDENCE");
        issue.set("evidence",com.readict.lexical.core.DictionaryJson.JSON.valueToTree(java.util.Map.of("ruleId","ffmpeg-strict-speex-v1","decoder","speex","result","INVALID_PACKET")));
        rewriteIssue(root,issue,manifest);assertThat(new AudioBundleValidator().validate(root,true)).isNotNull();
        issue.put("code","AUDIO_PROCESS_TIMEOUT");rewriteIssue(root,issue,manifest);assertThatThrownBy(()->new AudioBundleValidator().validate(root,true)).hasMessage("AUDIO_UNAPPROVED_FAILURE");
    }
    private static void rewriteIssue(Path root,tools.jackson.databind.JsonNode issue,tools.jackson.databind.JsonNode manifest) throws Exception{
        Files.writeString(root.resolve("issues.jsonl"),com.readict.lexical.core.DictionaryJson.json(issue)+"\n");
        for(var f:manifest.path("files"))if(f.path("name").asText().equals("issues.jsonl"))((tools.jackson.databind.node.ObjectNode)f).put("sizeBytes",Files.size(root.resolve("issues.jsonl"))).put("sha256",AudioFiles.digestFile(root.resolve("issues.jsonl")));
        Files.writeString(root.resolve("manifest.json"),com.readict.lexical.core.DictionaryJson.json(manifest)+"\n");
    }

    @Test void manifestRejectsCoercedBooleansAndNumericStrings() throws Exception{
        Path root=AudioFixtures.create(tmp.resolve("types"),1);var manifest=(tools.jackson.databind.node.ObjectNode)AudioFiles.object(root.resolve("manifest.json"));manifest.put("diagnosticOnly","false");
        Files.writeString(root.resolve("manifest.json"),com.readict.lexical.core.DictionaryJson.json(manifest)+"\n");assertThatThrownBy(()->new AudioBundleValidator().validate(root)).hasMessage("AUDIO_SCHEMA_INVALID");
        manifest.put("diagnosticOnly",false);((tools.jackson.databind.node.ObjectNode)manifest.path("sources").get(0)).put("sizeBytes","10");Files.writeString(root.resolve("manifest.json"),com.readict.lexical.core.DictionaryJson.json(manifest)+"\n");assertThatThrownBy(()->new AudioBundleValidator().validate(root)).hasMessage("AUDIO_INTEGER_INVALID");
    }

}
