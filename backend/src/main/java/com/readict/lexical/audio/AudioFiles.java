package com.readict.lexical.audio;

import static com.readict.lexical.core.DictionaryJson.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/** Byte and path checks shared by the offline importer and private storage. */
public final class AudioFiles {
    private AudioFiles() {}
    public static Path safe(Path root,String key) throws IOException {
        check(key!=null&&!key.isEmpty()&&!key.contains("\\")&&!key.contains(":")&&!key.chars().anyMatch(c->c<32),"AUDIO_PATH_INVALID");
        Path base=root.toRealPath(),p=base;
        for(String part:key.split("/",-1)){
            check(!part.isEmpty()&&!part.equals(".")&&!part.equals(".."),"AUDIO_PATH_INVALID");p=p.resolve(part);
        }
        check(p.normalize().startsWith(base),"AUDIO_PATH_ESCAPE");
        try{
            var attrs=Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            check(!attrs.isSymbolicLink(),"AUDIO_PATH_ESCAPE");Path actual=p.toRealPath();check(actual.startsWith(base),"AUDIO_PATH_ESCAPE");return actual;
        }catch(NoSuchFileException missing){
            Path ancestor=p.getParent();
            while(!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS)){ancestor=ancestor.getParent();check(ancestor!=null&&ancestor.startsWith(base),"AUDIO_PATH_ESCAPE");}
            check(!Files.isSymbolicLink(ancestor)&&ancestor.toRealPath().startsWith(base),"AUDIO_PATH_ESCAPE");return p;
        }
    }
    public static String digestFile(Path path) throws IOException {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            try(var in=Files.newInputStream(path)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)hash.update(buffer,0,n);}
            return HexFormat.of().formatHex(hash.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static void rows(Path file,Consumer<JsonNode> accept) throws IOException {
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try(var in=new BufferedReader(new InputStreamReader(Files.newInputStream(file),decoder))) {
            var line=new StringBuilder();int c;
            while((c=in.read())!=-1){check(c!='\r'&&c!=0xfeff,"AUDIO_ENCODING_INVALID");if(c=='\n'){check(!line.isEmpty()&&line.toString().getBytes(StandardCharsets.UTF_8).length<=524288,"AUDIO_LINE_INVALID");accept.accept(parse(line.toString()));line.setLength(0);}else{line.append((char)c);check(line.length()<=524288,"AUDIO_LINE_LIMIT");}}
            check(line.isEmpty(),"AUDIO_FINAL_LF_REQUIRED");
        }
    }
    public static JsonNode object(Path path) throws IOException {
        check(Files.size(path)<=524288,"AUDIO_LINE_LIMIT");var result=new ArrayList<JsonNode>();rows(path,result::add);check(result.size()==1,"AUDIO_JSON_INVALID");return result.getFirst();
    }
    public static void deleteOwnedTree(Path root) throws IOException {
        // Only callers' freshly created private staging directories are accepted.
        try(var walk=Files.walk(root)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
    }
}
