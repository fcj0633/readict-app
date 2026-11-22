package com.readict.lexical.audio;

import static com.readict.lexical.audio.AudioFiles.*;
import static com.readict.lexical.core.DictionaryJson.*;
import com.readict.lexical.core.DictionaryJson.DictionaryFailure;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AudioStorage {
    private static final Pattern KEY=Pattern.compile("media/([0-9a-f]{2})/([0-9a-f]{64})\\.mp3");
    private final String configured;
    private final String profile;
    private volatile Path canonicalRoot;
    private final Set<String> preparedPrefixes=ConcurrentHashMap.newKeySet();
    public AudioStorage(@Value("${readict.audio.storage-root:}") String root,Environment environment){
        configured=root;profile=environment.getActiveProfiles().length==1?environment.getActiveProfiles()[0]:"invalid";
    }
    public boolean configured(){return !configured.isBlank();}
    public Path root() throws IOException {
        Path ready=canonicalRoot;if(ready!=null)return ready;
        synchronized(this){
            if(canonicalRoot!=null)return canonicalRoot;
            check(configured(),"AUDIO_NOT_READY");Path path=Path.of(configured).toAbsolutePath().normalize();
            check((profile.equals("test")||profile.equals("dev"))&&path.getFileName().toString().equals("readict_"+profile),"AUDIO_STORAGE_ENVIRONMENT_INVALID");
            Files.createDirectories(path);check(!Files.isSymbolicLink(path),"AUDIO_PATH_ESCAPE");Path real=path.toRealPath();
            check(real.getFileName().toString().equals("readict_"+profile),"AUDIO_STORAGE_ENVIRONMENT_INVALID");canonicalRoot=real;return real;
        }
    }
    private Path address(String key,boolean create) throws IOException {
        var match=KEY.matcher(key);check(match.matches()&&match.group(2).startsWith(match.group(1)),"AUDIO_PATH_INVALID");
        Path base=root(),parent=base.resolve("media").resolve(match.group(1));
        if(create&&!preparedPrefixes.contains(match.group(1))){
            // Validate existing ancestors before creation, then the real parent again.
            Path checked=safe(base,"media/"+match.group(1));Files.createDirectories(checked);
            check(checked.toRealPath().startsWith(base),"AUDIO_PATH_ESCAPE");preparedPrefixes.add(match.group(1));
        }
        // Re-resolve for every operation: cached setup never permits a replaced junction to escape.
        Path actualParent=parent.toRealPath();check(actualParent.startsWith(base),"AUDIO_PATH_ESCAPE");
        return actualParent.resolve(match.group(2)+".mp3");
    }
    private static void regular(Path path,long bytes) throws IOException {
        var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        check(!attrs.isSymbolicLink(),"AUDIO_PATH_ESCAPE");check(attrs.isRegularFile()&&attrs.size()==bytes,"AUDIO_NOT_FOUND");
    }
    public Path readable(String key,long bytes) throws IOException {
        try{Path target=address(key,false);regular(target,bytes);return target;}
        catch(NoSuchFileException missing){throw new DictionaryFailure("AUDIO_NOT_FOUND");}
    }
    public void publish(Path source,String key,String sha,long bytes) throws IOException {
        check(key.endsWith("/"+sha+".mp3")&&bytes>0&&bytes<=2097152,"AUDIO_ASSET_INVALID");
        Path target=address(key,true);
        try{
            regular(target,bytes);check(digestFile(target).equals(sha),"AUDIO_STORAGE_CONFLICT");return;
        }catch(NoSuchFileException missing){/* Publish into this verified private prefix. */}
        catch(DictionaryFailure invalid){if(invalid.code.equals("AUDIO_NOT_FOUND"))throw new DictionaryFailure("AUDIO_STORAGE_CONFLICT");throw invalid;}
        Path staged=Files.createTempFile(target.getParent(),".audio-",".tmp");
        try{
            Files.copy(source,staged,StandardCopyOption.REPLACE_EXISTING);
            check(Files.size(staged)==bytes&&digestFile(staged).equals(sha),"AUDIO_MEDIA_INVALID");
            // MVP uses normal filesystem durability; power-loss recovery remains a later acceptance item.
            try{Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE);}
            catch(FileAlreadyExistsException|AccessDeniedException concurrent){
                // Windows can report access denied when another publisher already installed this hash.
                // Reuse only a complete matching target; all other permission errors still fail.
                if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw concurrent;
                regular(target,bytes);check(digestFile(target).equals(sha),"AUDIO_STORAGE_CONFLICT");
            }
        }finally{Files.deleteIfExists(staged);}
    }
}
