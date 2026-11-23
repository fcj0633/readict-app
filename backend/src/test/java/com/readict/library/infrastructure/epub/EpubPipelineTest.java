package com.readict.library.infrastructure.epub;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.readict.library.domain.EpubLimits;
import tools.jackson.databind.json.JsonMapper;

class EpubPipelineTest {
    @TempDir Path temp;

    @Test void producesDeterministicArtifactsAndSafeContent() throws Exception {
        EpubPipeline pipeline = new EpubPipeline(EpubLimits.defaults(), JsonMapper.builder().build());
        Path source = EpubFixtures.epub3(temp.resolve("book.epub"));
        var first = pipeline.build(source, temp.resolve("out1"), "aa".repeat(32), null);
        var second = pipeline.build(source, temp.resolve("out2"), "aa".repeat(32), null);

        assertThat(first.artifactHash()).isEqualTo(second.artifactHash());
        assertThat(first.chapters()).hasSize(2);
        assertThat(first.totalUtf16()).isPositive();
        assertThat(Files.readAllBytes(temp.resolve("out1").resolve("index.json")))
                .isEqualTo(Files.readAllBytes(temp.resolve("out2").resolve("index.json")));

        Map<String, String> entries = readZip(first.directory().resolve("reader.epub"));
        String chapter = entries.get("OEBPS/Text/ch1.xhtml");
        assertThat(chapter).contains("Call me Ishmael.").contains("Chapter 1");
        assertThat(chapter).doesNotContain("<script").doesNotContain("onload").doesNotContain("onclick")
                .doesNotContain("http://evil.example").doesNotContain("javascript:").doesNotContain("invisible text")
                .doesNotContain("<iframe").doesNotContain("<form");
        assertThat(chapter).contains("Content-Security-Policy");
        String css = entries.get("OEBPS/Styles/style.css");
        assertThat(css).contains("color:").doesNotContain("@import").doesNotContain("url(")
                .doesNotContain("position").doesNotContain("expression");
        assertThat(entries).containsKey("mimetype").containsKey("OEBPS/Images/cover.png")
                .containsKey("META-INF/container.xml").containsKey("OEBPS/content.opf");
        assertThat(entries.get("mimetype")).isEqualTo("application/epub+zip");
        assertThat(Files.exists(first.directory().resolve("manifest.json"))).isTrue();
        assertThat(Files.exists(first.directory().resolve("cover.png"))).isTrue();
    }

    @Test void hostileDocumentLosesEveryExecutableConstruct() throws Exception {
        EpubPipeline pipeline = new EpubPipeline(EpubLimits.defaults(), JsonMapper.builder().build());
        var built = pipeline.build(EpubFixtures.hostile(temp.resolve("hostile.epub")), temp.resolve("out"), "bb".repeat(32), null);
        Map<String, String> entries = readZip(built.directory().resolve("reader.epub"));
        String chapter = entries.get("OEBPS/Text/ch1.xhtml");
        assertThat(chapter).contains("Safe text.").doesNotContain("alert").doesNotContain("<script")
                .doesNotContain("<iframe").doesNotContain("onload").doesNotContain("background:url");
        String index = Files.readString(built.directory().resolve("index.json"), StandardCharsets.UTF_8);
        assertThat(index).doesNotContain("invisible text");
    }

    @Test void indexBlocksHashMatchTheirText() throws Exception {
        EpubPipeline pipeline = new EpubPipeline(EpubLimits.defaults(), JsonMapper.builder().build());
        var built = pipeline.build(EpubFixtures.epub3(temp.resolve("b.epub")), temp.resolve("out"), "cc".repeat(32), null);
        var json = JsonMapper.builder().build().readTree(Files.readAllBytes(built.directory().resolve("index.json")));
        for (var chapter : json.path("chapters")) {
            for (var block : chapter.path("blocks")) {
                String text = block.path("text").asString();
                assertThat(block.path("utf16Length").asInt()).isEqualTo(text.length());
                assertThat(block.path("textHash").asString()).isEqualTo(TextIndexer.hash(text));
                for (var sentence : block.path("sentences")) {
                    assertThat(sentence.path("start").asInt()).isGreaterThanOrEqualTo(0);
                    assertThat(sentence.path("end").asInt()).isLessThanOrEqualTo(text.length());
                }
            }
        }
    }

    private static Map<String, String> readZip(Path file) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                try (var in = zip.getInputStream(entry)) {
                    result.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return result;
    }
}
