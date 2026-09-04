package io.github.xw66.knowflowai.ingestion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

public final class DocumentParser {
    private DocumentParser() {}

    public static List<Chunk> parse(byte[] bytes, String mediaType) {
        var chunks = new ArrayList<Chunk>();
        try {
            switch (mediaType) {
                case "application/pdf" -> {
                    try (var document = Loader.loadPDF(bytes)) {
                        if (document.isEncrypted()) throw new IllegalArgumentException("暂不支持加密 PDF");
                        if (document.getNumberOfPages() > 1000) throw new IllegalArgumentException("PDF 超过 1000 页限制");
                        var stripper = new PDFTextStripper();
                        stripper.setSortByPosition(true);
                        int remaining = 10 * 1024 * 1024;
                        for (int page = 1; page <= document.getNumberOfPages(); page++) {
                            stripper.setStartPage(page);
                            stripper.setEndPage(page);
                            var output = new BoundedText(remaining);
                            stripper.writeText(document, output);
                            remaining -= output.text.length();
                            append(chunks, output.text.toString(), page, 0);
                        }
                    }
                }
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                    try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                        // DOCX 没有稳定页码；按正文、表格单元格中的段落顺序编号，不读取外部链接。
                        docx(document.getBodyElements(), chunks, new int[]{0, 10 * 1024 * 1024}, 0);
                    }
                }
                case "text/plain", "text/markdown" -> append(chunks,
                        StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(), null, 0);
                default -> throw new IllegalArgumentException("不支持的文档类型");
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("文档损坏、加密、超限或无法解析", exception);
        }
        if (chunks.isEmpty()) throw new IllegalArgumentException("没有可提取文本，扫描文件需要 OCR");
        return chunks;
    }

    private static void docx(List<IBodyElement> elements, List<Chunk> chunks, int[] counters, int depth) {
        if (depth > 20) throw new IllegalArgumentException("表格嵌套超过限制");
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                String text = paragraph.getText();
                counters[1] -= text.length();
                if (counters[1] < 0) throw new IllegalArgumentException("正文超过限制");
                int number = ++counters[0];
                if (!text.isBlank()) append(chunks, text, null, number);
            } else if (element instanceof XWPFTable table) {
                for (var row : table.getRows()) {
                    for (var cell : row.getTableCells()) docx(cell.getBodyElements(), chunks, counters, depth + 1);
                }
            }
        }
    }

    private static void append(List<Chunk> chunks, String text, Integer page, int paragraph) {
        if (text.isBlank()) return;
        for (var chunk : TextTaskProcessor.split(text)) {
            chunks.add(new Chunk(paragraph == 0 ? chunk.paragraphNumber() : paragraph, page, chunk.content()));
            if (chunks.size() > 20000) throw new IllegalArgumentException("分块数量超过限制");
        }
    }

    // 限制提取出的正文，而不只是压缩文件大小；PDF 内部对象仍由 PDFBox 解析，不能替代进程内存隔离。
    private static final class BoundedText extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;
        private BoundedText(int limit) { this.limit = limit; }
        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            if (length > limit - text.length()) throw new IOException("PDF 正文超过限制");
            text.append(buffer, offset, length);
        }
        @Override public void flush() {}
        @Override public void close() {}
    }

    public record Chunk(int paragraphNumber, Integer pageNumber, String content) {}
}
