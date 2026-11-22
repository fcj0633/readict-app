package com.readict.lexical.audio;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.readict.lexical.api.DictionaryAudioController;

class AudioRangeTest {
    @Test void rangesHaveExactBoundaries(){assertThat(DictionaryAudioController.parseRange("bytes=2-4",10)).containsExactly(2,4);assertThat(DictionaryAudioController.parseRange("bytes=8-99",10)).containsExactly(8,9);assertThat(DictionaryAudioController.parseRange("bytes=-3",10)).containsExactly(7,9);assertThat(DictionaryAudioController.parseRange("bytes=5-",10)).containsExactly(5,9);}
    @Test void malformedUnsatisfiedAndMultipleRangesAreRejected(){for(String value:new String[]{"bytes=10-","bytes=-0","bytes=5-3","bytes=0-1,4-5","items=0-2","bytes=999999999999999999999-"})assertThat(DictionaryAudioController.parseRange(value,10)).isNull();}
}
