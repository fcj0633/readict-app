package com.readict.favorites.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import com.readict.lexical.api.DictionaryLookupDtos.SourceView;

/** Position identity, independent of the clicked word and display titles. */
public final class ContextKey {
    private ContextKey() {}
    public static byte[] of(UUID origin, SourceView source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{origin.toString(), source.pipelineVersion(), source.artifactHash(),
                    source.spineHref(), source.blockId()}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            digest.update(ByteBuffer.allocate(8).putInt(source.sentenceStart()).putInt(source.sentenceEnd()).array());
            return digest.digest();
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
