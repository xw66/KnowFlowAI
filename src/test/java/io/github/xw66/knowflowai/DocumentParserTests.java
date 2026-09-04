package io.github.xw66.knowflowai;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import io.github.xw66.knowflowai.ingestion.DocumentParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParserTests {
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    static byte[] pdf(boolean encrypted, String... pages) throws Exception {
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            for (String text : pages) {
                var page = new PDPage();
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            if (encrypted) document.protect(new StandardProtectionPolicy("owner", "password", new AccessPermission()));
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    static byte[] docx() throws Exception {
        try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("第一段 😀");
            document.createParagraph();
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("表格左侧");
            table.getRow(0).getCell(1).setText("表格右侧");
            document.createParagraph().createRun().setText("末段");
            document.write(bytes);
            return bytes.toByteArray();
        }
    }

    @Test
    void pdfRetainsPhysicalPagesIncludingBlankGaps() throws Exception {
        var chunks = DocumentParser.parse(pdf(false, "First page", "", "Third page"), "application/pdf");
        assertThat(chunks).extracting(DocumentParser.Chunk::pageNumber).containsExactly(1, 3);
        assertThat(chunks).extracting(DocumentParser.Chunk::content).containsExactly("First page", "Third page");
    }

    @Test
    void docxPreservesBodyTableOrderAndParagraphNumbers() throws Exception {
        var chunks = DocumentParser.parse(docx(), DOCX);
        assertThat(chunks).extracting(DocumentParser.Chunk::content).containsExactly("第一段 😀", "表格左侧", "表格右侧", "末段");
        assertThat(chunks).extracting(DocumentParser.Chunk::paragraphNumber).containsExactly(1, 3, 4, 5);
        assertThat(chunks).allMatch(chunk -> chunk.pageNumber() == null);
    }

    @Test
    void encryptedEmptyAndMalformedDocumentsAreRejected() throws Exception {
        byte[] encrypted = pdf(true, "Private");
        byte[] empty = pdf(false, "");
        assertThatThrownBy(() -> DocumentParser.parse(encrypted, "application/pdf")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentParser.parse(empty, "application/pdf")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentParser.parse("%PDF-broken".getBytes(StandardCharsets.UTF_8), "application/pdf")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentParser.parse(new byte[]{1, 2, 3}, DOCX)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pdfPageLimitIsEnforced() throws Exception {
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            for (int i = 0; i < 1001; i++) document.addPage(new PDPage());
            document.save(bytes);
            assertThatThrownBy(() -> DocumentParser.parse(bytes.toByteArray(), "application/pdf")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
