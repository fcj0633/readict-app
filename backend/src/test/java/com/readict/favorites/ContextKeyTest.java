package com.readict.favorites;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.readict.favorites.application.ContextKey;
import com.readict.lexical.api.DictionaryLookupDtos.*;
class ContextKeyTest {
    SourceView source(int wordStart,String pipeline,String block,int sentenceStart) {
        return new SourceView(UUID.randomUUID(),"a".repeat(64),pipeline,"章节.xhtml",block,sentenceStart,30,"sentence","synthetic","word",wordStart,wordStart+4,"cfi","cfi",new Quote("synthetic","",""));
    }
    @Test void fixedUtf8BigEndianVectorAndWordRangeIndependence() {
        UUID origin=UUID.fromString("00000000-0000-4000-8000-000000000001");
        var key=ContextKey.of(origin,source(0,"pipeline","b-1",2));
        assertThat(HexFormat.of().formatHex(key)).isEqualTo("f56825b41ac69705de7efb37fd8b331f30fee2b7a04869a105914d09fcf66922");
        assertThat(ContextKey.of(origin,source(5,"pipeline","b-1",2))).isEqualTo(key);
        assertThat(ContextKey.of(origin,source(0,"pipeline2","b-1",2))).isNotEqualTo(key);
        assertThat(ContextKey.of(origin,source(0,"pipeline","b-2",2))).isNotEqualTo(key);
        assertThat(ContextKey.of(origin,source(0,"pipeline","b-1",3))).isNotEqualTo(key);
        assertThat(ContextKey.of(UUID.randomUUID(),source(0,"pipeline","b-1",2))).isNotEqualTo(key);
    }
}
