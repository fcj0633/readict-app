package com.readict.reading.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.readict.library.infrastructure.persistence.entity.BookArtifactEntity;
import com.readict.library.infrastructure.storage.BookStorage;
import com.readict.shared.web.ApiProblem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded cache over the immutable per-artifact index.json (max four artifacts). */
@Component
public class ReadingIndexCache {
    private static final int CAPACITY = 4;
    private final BookStorage storage;
    private final ObjectMapper json;
    private final Map<UUID, JsonNode> cache = new LinkedHashMap<>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, JsonNode> eldest) {
            return size() > CAPACITY;
        }
    };

    public ReadingIndexCache(BookStorage storage, ObjectMapper json) {
        this.storage = storage;
        this.json = json;
    }

    public synchronized JsonNode chapter(BookArtifactEntity artifact, String spineHref) {
        JsonNode index = cache.get(artifact.getId());
        if (index == null) {
            Path path = storage.resolve(artifact.getIndexStorageKey());
            try (var in = Files.newInputStream(path)) {
                index = json.readTree(in);
            } catch (IOException ex) {
                throw new ApiProblem(503, "STORAGE_UNAVAILABLE", "阅读数据暂不可用");
            }
            cache.put(artifact.getId(), index);
        }
        for (JsonNode chapter : index.path("chapters")) {
            if (spineHref.equals(chapter.path("spineHref").asString())) return chapter;
        }
        throw new ApiProblem(422, "ANCHOR_INVALID", "章节索引不存在");
    }
}
