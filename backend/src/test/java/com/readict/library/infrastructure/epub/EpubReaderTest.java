package com.readict.library.infrastructure.epub;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.readict.library.domain.EpubLimits;

class EpubReaderTest {
    @TempDir Path temp;

    @Test void readsEpub3MetadataSpineAndNavigation() {
        var epub = new EpubReader(EpubLimits.defaults()).read(EpubFixtures.epub3(temp.resolve("book.epub")));
        assertThat(epub.title()).isEqualTo("Test Book");
        assertThat(epub.author()).isEqualTo("Test Author");
        assertThat(epub.language()).isEqualTo("en");
        assertThat(epub.spine()).hasSize(2);
        assertThat(epub.spine().get(0).entryName()).isEqualTo("OEBPS/Text/ch1.xhtml");
        assertThat(epub.spine().get(0).title()).isEqualTo("Chapter One");
        assertThat(epub.tocSource()).isEqualTo("NAV");
        assertThat(epub.toc()).hasSize(2);
        assertThat(epub.cover()).isNotNull();
        assertThat(epub.cover().mime()).isEqualTo("image/png");
        assertThat(epub.mediaTypes()).containsEntry("OEBPS/Styles/style.css", "text/css");
    }

    @Test void readsEpub2Ncx() {
        var epub = new EpubReader(EpubLimits.defaults()).read(EpubFixtures.epub2(temp.resolve("book2.epub")));
        assertThat(epub.spine()).hasSize(2);
        assertThat(epub.tocSource()).isEqualTo("NCX");
        assertThat(epub.spine().get(1).title()).isEqualTo("Chapter Two");
    }

    @Test void rejectsUnsafeAndUnsupportedPackages() {
        EpubReader reader = new EpubReader(EpubLimits.defaults());
        assertThat(code(() -> reader.read(EpubFixtures.fixedLayout(temp.resolve("fixed.epub")))))
                .isEqualTo("EPUB_FIXED_LAYOUT");
        assertThat(code(() -> reader.read(EpubFixtures.encrypted(temp.resolve("enc.epub")))))
                .isEqualTo("EPUB_ENCRYPTED");
        assertThat(code(() -> reader.read(EpubFixtures.badMimetype(temp.resolve("bad.epub")))))
                .isEqualTo("EPUB_INVALID_MIMETYPE");
    }

    @Test void enforcesEntryBudgetOnRealDecompression() {
        EpubLimits tight = new EpubLimits(EpubLimits.defaults().maxSourceBytes(), 2500, 1024,
                EpubLimits.defaults().maxTotalBytes(), 512, 16, 1000, 8L * 1024 * 1024,
                96L * 1024 * 1024, 8L * 1024 * 1024, 4L * 1024 * 1024, 4096, 65536, 60);
        var code = code(() -> new EpubReader(tight).read(EpubFixtures.zipBomb(temp.resolve("bomb.epub"), 4096)));
        assertThat(code).isEqualTo("EPUB_ENTRY_TOO_LARGE");
    }

    @Test void resolvesRelativeReferencesAndRejectsEscapes() {
        assertThat(EpubReader.resolve("OEBPS/Text/ch1.xhtml", "../Styles/a.css")).isEqualTo("OEBPS/Styles/a.css");
        assertThat(EpubReader.resolve("OEBPS/Text/ch1.xhtml", "%2e%2e/%2e%2e/%2e%2e/escape.xhtml")).isNull();
        assertThat(EpubReader.resolve("OEBPS/Text/ch1.xhtml", "/abs.xhtml")).isNull();
        assertThat(EpubReader.resolve("OEBPS/Text/ch1.xhtml", "http://x/y")).isNull();
    }

    private static String code(Runnable action) {
        try {
            action.run();
            return "NO_FAILURE";
        } catch (ImportFailure failure) {
            return failure.code();
        }
    }
}
