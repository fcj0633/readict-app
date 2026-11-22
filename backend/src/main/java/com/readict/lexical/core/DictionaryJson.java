package com.readict.lexical.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

public final class DictionaryJson {
    private DictionaryJson() {}
    public static final ObjectMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    public static String json(Object v) { return JSON.writeValueAsString(v); }
    public static JsonNode parse(String s) { JsonNode n=JSON.readTree(s);validUnicode(n);return n; }
    private static void validString(String s){for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c)){check(i+1<s.length()&&Character.isLowSurrogate(s.charAt(++i)),"UNPAIRED_SURROGATE");}else check(!Character.isLowSurrogate(c),"UNPAIRED_SURROGATE");}}
    private static void validUnicode(JsonNode n){if(n.isString())validString(n.asText());else if(n.isObject()){for(String key:n.propertyNames()){validString(key);validUnicode(n.path(key));}}else if(n.isArray())for(var child:n)validUnicode(child);}
    public static void check(boolean ok, String code) { if (!ok) throw new DictionaryFailure(code); }
    public static String digest(byte[] b) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); } catch(Exception e) { throw new IllegalStateException(e); } }
    public static String hash(Object v) { return digest(json(v).getBytes(StandardCharsets.UTF_8)); }
    public static UUID uuid(String hash) { byte[] b=HexFormat.of().parseHex(hash.substring(0,32)); b[6]=(byte)((b[6]&15)|128); b[8]=(byte)((b[8]&63)|128); return new UUID(java.nio.ByteBuffer.wrap(b).getLong(),java.nio.ByteBuffer.wrap(b).getLong(8)); }
    public static UUID identity(Object tuple) { return uuid(hash(tuple)); }
    public static String normalize(String value) {
        check(value!=null && value.codePointCount(0,value.length())<=128 && value.getBytes(StandardCharsets.UTF_8).length<=512,"UNSUPPORTED_QUERY");
        for(int i=0;i<value.length();i++) { char c=value.charAt(i); if(Character.isHighSurrogate(c)) {check(i+1<value.length()&&Character.isLowSurrogate(value.charAt(++i)),"UNSUPPORTED_QUERY");} else check(!Character.isLowSurrogate(c),"UNSUPPORTED_QUERY"); }
        check(value.codePoints().noneMatch(c->Character.getType(c)==Character.CONTROL || Character.getType(c)==Character.FORMAT),"UNSUPPORTED_QUERY");
        String k=Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('‘','\'').replace('’','\'').replaceAll("^[^\\p{L}\\p{N}\\p{M}]+|[^\\p{L}\\p{N}\\p{M}]+$","");
        check(k.matches("[\\p{L}\\p{N}\\p{M}]+(?:[-'][\\p{L}\\p{N}\\p{M}]+)*") && Pattern.compile("\\p{L}").matcher(k).find(),"UNSUPPORTED_QUERY"); return k;
    }
    public static final class DictionaryFailure extends RuntimeException {
        public final String code;
        public DictionaryFailure(String code) { super(code); this.code=code; }
    }
}
