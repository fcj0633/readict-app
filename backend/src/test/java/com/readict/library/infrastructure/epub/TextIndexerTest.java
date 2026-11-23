package com.readict.library.infrastructure.epub;

import static org.assertj.core.api.Assertions.*;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TextIndexerTest {
    @Test void mergesHonorificAbbreviations() {
        var sentences = TextIndexer.sentences("Dr. Smith arrived. The children are running.");
        assertThat(sentences).hasSize(2);
        assertThat(sentences.get(0).start()).isZero();
        assertThat(sentences.get(0).kind()).isEqualTo("SENTENCE");
        assertThat(sentences.get(1).start()).isEqualTo(19);
    }

    @Test void marksTrailingFragment() {
        var sentences = TextIndexer.sentences("It was the best of times, it was");
        assertThat(sentences).hasSize(1);
        assertThat(sentences.get(0).kind()).isEqualTo("FRAGMENT");
    }

    @Test void hashIsStableAndCaseSensitive() {
        assertThat(TextIndexer.hash("abc")).isEqualTo(TextIndexer.hash("abc"));
        assertThat(TextIndexer.hash("abc")).isNotEqualTo(TextIndexer.hash("ABC"));
        assertThat(TextIndexer.hash("abc")).hasSize(64).isEqualTo(TextIndexer.hash("abc").toLowerCase(Locale.ROOT));
    }
}
