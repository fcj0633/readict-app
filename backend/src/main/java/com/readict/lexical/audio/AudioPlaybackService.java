package com.readict.lexical.audio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.UUID;
import com.readict.lexical.infrastructure.persistence.mapper.AudioMapper;
import com.readict.lexical.core.DictionaryImporter;
import com.readict.lexical.core.DictionaryJson.DictionaryFailure;
import org.springframework.stereotype.Service;

/** Resolves one immutable READY recording without exposing storage paths in API data. */
@Service
public class AudioPlaybackService {
    public record Resource(Path path,long bytes,String sha256) {}
    private final AudioMapper db;
    private final AudioStorage storage;
    public AudioPlaybackService(AudioMapper db,AudioStorage storage){this.db=db;this.storage=storage;}
    public Resource resolve(UUID audioId) throws IOException {
        var active=db.active();
        if(!storage.configured()||active==null||active.get("dataset_id")==null)throw new DictionaryFailure("AUDIO_NOT_READY");
        var resource=db.resource(DictionaryImporter.bytes(audioId.toString()));
        if(resource==null)throw new DictionaryFailure("AUDIO_NOT_FOUND");
        long length=((Number)resource.get("bytes")).longValue();
        Path path=storage.readable((String)resource.get("storage_key"),length);
        String sha=HexFormat.of().formatHex((byte[])resource.get("output_sha"));
        if(!AudioFiles.digestFile(path).equals(sha))throw new IOException("AUDIO_CONTENT_CHANGED");
        return new Resource(path,length,sha);
    }
}
