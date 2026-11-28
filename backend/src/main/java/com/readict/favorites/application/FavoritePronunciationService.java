package com.readict.favorites.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import com.readict.favorites.infrastructure.persistence.FavoriteRepository;
import com.readict.lexical.api.DictionaryLookupDtos.*;
import com.readict.lexical.audio.AudioCatalog;
import com.readict.lexical.core.DictionaryJson.DictionaryFailure;
import com.readict.shared.web.ApiProblem;

@Service
public class FavoritePronunciationService {
    private final FavoriteRepository db;
    private final AudioCatalog audio;
    public FavoritePronunciationService(FavoriteRepository db,AudioCatalog audio) { this.db=db; this.audio=audio; }

    public PronunciationView pronunciations(UUID user,UUID favorite) {
        var row=db.word(user,favorite,false);
        if(row==null) throw new ApiProblem(404,"FAVORITE_NOT_FOUND","收藏不存在");
        try {
            var value=audio.find(FavoriteRepository.text(row,"lemma"),FavoriteRepository.text(row,"normalization_version"));
            return new PronunciationView(value.status(),value.word(),value.source(),value.audioDatasetId(),
                    value.audioDatasetVersion(),value.items().stream()
                    .map(item -> new PronunciationItem(item.audioId(),item.accent(),item.mime(),item.durationMs())).toList());
        } catch(DictionaryFailure error) {
            throw new ApiProblem(503,"AUDIO_NOT_READY","音频暂不可用");
        }
    }
}
