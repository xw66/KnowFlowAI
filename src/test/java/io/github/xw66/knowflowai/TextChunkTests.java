package io.github.xw66.knowflowai;

import io.github.xw66.knowflowai.ingestion.TextTaskProcessor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkTests {
    @Test
    void preservesParagraphsMarkdownAndUnicodeWithoutSplittingSurrogates() {
        String longText = "😀".repeat(801);
        var chunks = TextTaskProcessor.split("\uFEFF# 标题\r\n\r\n" + longText + "\n \n最后一段");
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).content()).isEqualTo("# 标题");
        assertThat(chunks.get(1).paragraphNumber()).isEqualTo(2);
        assertThat(chunks.get(2).paragraphNumber()).isEqualTo(2);
        assertThat(chunks.get(1).content() + chunks.get(2).content()).isEqualTo(longText);
        assertThat(chunks.get(2).content()).isEqualTo("😀");
        assertThat(chunks.get(3).paragraphNumber()).isEqualTo(3);
        assertThatThrownBy(() -> TextTaskProcessor.split("\uFEFF \n\n ")).isInstanceOf(IllegalArgumentException.class);
    }
}
