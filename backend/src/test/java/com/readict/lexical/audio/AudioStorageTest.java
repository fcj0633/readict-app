package com.readict.lexical.audio;

import static org.assertj.core.api.Assertions.*;
import static com.readict.lexical.core.DictionaryJson.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class AudioStorageTest {
    @TempDir Path tmp;
    AudioStorage storage(){var env=new MockEnvironment();env.setActiveProfiles("test");return new AudioStorage(tmp.resolve("readict_test").toString(),env);}
    static String key(String sha){return "media/"+sha.substring(0,2)+"/"+sha+".mp3";}
    void junction(Path link,Path target) throws Exception{
        if(!System.getProperty("os.name").startsWith("Windows")){Files.createSymbolicLink(link,target);return;}
        Path script=tmp.resolve("make-test-junction.ps1");
        Files.writeString(script,"param([string]$Link,[string]$Target)\n$ErrorActionPreference='Stop'\nNew-Item -ItemType Junction -Path $Link -Target $Target | Out-Null\n");
        var child=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-File",script.toString(),"-Link",link.toString(),"-Target",target.toString()).redirectErrorStream(true).start();
        if(!child.waitFor(10,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor();fail("Junction helper timed out");}
        assertThat(child.exitValue()).as(new String(child.getInputStream().readAllBytes())).isZero();
    }
    @Test void publicationIsIdempotentAndRejectsDifferentBytesAtSameKey() throws Exception{
        var s=storage();Path source=tmp.resolve("self-authored.fixture");byte[] bytes="self-authored audio storage fixture".getBytes();Files.write(source,bytes);String sha=digest(bytes),key=key(sha);
        s.publish(source,key,sha,bytes.length);assertThat(Files.readAllBytes(s.readable(key,bytes.length))).isEqualTo(bytes);s.publish(source,key,sha,bytes.length);
        Files.writeString(s.root().resolve(key),"changed");assertThatThrownBy(()->s.publish(source,key,sha,bytes.length)).hasMessage("AUDIO_STORAGE_CONFLICT");
        assertThatThrownBy(()->s.readable("../outside",1)).hasMessage("AUDIO_PATH_INVALID");
    }
    @Test void replacedCachedPrefixCannotReadOrPublishOutsideRoot() throws Exception{
        var s=storage();byte[] bytes="self-authored cached prefix fixture".getBytes();String sha=digest(bytes),key=key(sha);Path source=tmp.resolve("source.fixture");Files.write(source,bytes);s.publish(source,key,sha,bytes.length);
        Path prefix=s.root().resolve(key).getParent(),outside=Files.createDirectory(tmp.resolve("outside"));Path file=outside.resolve(sha+".mp3");Files.write(file,bytes);
        Files.delete(s.root().resolve(key));Files.delete(prefix);junction(prefix,outside);
        try{
            assertThatThrownBy(()->s.readable(key,bytes.length)).hasMessage("AUDIO_PATH_ESCAPE");
            assertThatThrownBy(()->s.publish(source,key,sha,bytes.length)).hasMessage("AUDIO_PATH_ESCAPE");
            assertThatThrownBy(()->AudioFiles.safe(s.root(),key)).hasMessage("AUDIO_PATH_ESCAPE");
            assertThatThrownBy(()->AudioFiles.safe(s.root(),"media/"+sha.substring(0,2)+"/missing.mp3")).hasMessage("AUDIO_PATH_ESCAPE");
            assertThat(Files.readAllBytes(file)).isEqualTo(bytes);try(var list=Files.list(outside)){assertThat(list.count()).isEqualTo(1);}
        }finally{Files.deleteIfExists(prefix);}
    }
}
