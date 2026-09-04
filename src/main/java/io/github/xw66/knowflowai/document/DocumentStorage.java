package io.github.xw66.knowflowai.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Component
public class DocumentStorage {

    private final Path directory;
    private final int maxBytes;

    public DocumentStorage(@Value("${app.document.storage-directory}") String directory,
            @Value("${app.document.max-file-size}") DataSize maxSize) throws IOException {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        if (maxSize.toBytes() < 1 || maxSize.toBytes() >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("上传大小限制必须为正数且小于 2 GB");
        }
        this.maxBytes = (int) maxSize.toBytes();
        Files.createDirectories(this.directory);
    }

    public StoredFile store(MultipartFile upload) {
        String name = upload.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 255 || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名无效，不能包含路径或控制字符");
        }
        name = name.strip();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (name.lastIndexOf('.') < 1 || !Set.of("pdf", "docx", "md", "txt").contains(extension)) {
            throw unsupported();
        }
        if (upload.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "文件超过上传大小限制");
        }
        Path temporary = null;
        try (var input = upload.getInputStream()) {
            // ponytail: 单文件最多默认 10 MB，较大文件改为流式校验与摘要计算。
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "文件超过上传大小限制");
            }
            if (bytes.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能上传空文件");
            }
            String mediaType = validateType(extension, bytes);
            String key = UUID.randomUUID() + "." + extension;
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            temporary = Files.createTempFile(directory, ".upload-", ".part");
            Files.write(temporary, bytes);
            Files.move(temporary, directory.resolve(key), StandardCopyOption.ATOMIC_MOVE);
            return new StoredFile(name, key, hash, mediaType, bytes.length);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件存储暂时不可用");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        } finally {
            if (temporary != null) {
                deleteQuietly(temporary.getFileName().toString());
            }
        }
    }

    public byte[] read(String key) throws IOException {
        try { Files.readAttributes(directory,java.nio.file.attribute.BasicFileAttributes.class); }
        catch (java.nio.file.NoSuchFileException error) { throw new IOException("存储目录不可用",error); }
        var target = directory.resolve(key).normalize();
        if (!target.getParent().equals(directory) || Files.isSymbolicLink(target)) {
            throw new IOException("存储键无效");
        }
        try (var input = Files.newInputStream(target)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new IOException("文件超过读取限制");
            }
            return bytes;
        }
    }

    public FilePage listFiles(String afterKey, int limit) throws IOException {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("文件扫描页大小无效");
        try (Stream<Path> paths = Files.list(directory)) {
            var files = paths.filter(path -> path.getFileName().toString().compareTo(afterKey == null ? "" : afterKey) > 0)
                    .sorted().limit(limit + 1).map(path -> {
                        try {
                            var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
                            String key = path.getFileName().toString();
                            String status = Files.isSymbolicLink(path) ? "SYMLINK"
                                    : !attributes.isRegularFile() ? "DIRECTORY_OR_SPECIAL"
                                    : key.startsWith(".upload-") ? "TEMPORARY" : "CANDIDATE";
                            return new FileEntry(key,status,attributes.isRegularFile() ? attributes.size() : null);
                        } catch (IOException error) {
                            return new FileEntry(path.getFileName().toString(),"UNREADABLE",null);
                        }
                    }).toList();
            return new FilePage(files.size() > limit, files.subList(0, Math.min(limit, files.size())));
        }
    }

    public void deleteQuietly(String key) {
        var target = directory.resolve(key).normalize();
        if (!target.getParent().equals(directory)) {
            throw new IllegalArgumentException("存储键无效");
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            LoggerFactory.getLogger(DocumentStorage.class).atWarn().addKeyValue("storageKey", key)
                    .log("临时文件清理失败，需后续核对孤立文件");
        }
    }

    private static String validateType(String extension, byte[] bytes) {
        if (extension.equals("pdf")) {
            if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
                throw unsupported();
            }
            return "application/pdf";
        }
        if (extension.equals("docx")) {
            validateDocx(bytes);
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            if (text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文本文件不能只包含空白");
            }
            if (text.startsWith("%PDF-") || text.startsWith("PK\u0003\u0004")
                    || text.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')) {
                throw unsupported();
            }
            return extension.equals("md") ? "text/markdown" : "text/plain";
        } catch (CharacterCodingException exception) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "TXT 和 Markdown 必须使用 UTF-8 文本编码");
        }
    }

    private static void validateDocx(byte[] bytes) {
        var entries = new HashSet<String>();
        int total = 0;
        int count = 0;
        byte[] buffer = new byte[8192];
        // 仅检查容器结构，不提取正文；限制解压量与条目数，防止压缩炸弹。
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++count > 2000) {
                    throw unsupported();
                }
                if (!entry.isDirectory()) {
                    entries.add(entry.getName());
                }
                for (int read; (read = zip.read(buffer)) != -1;) {
                    total += read;
                    if (total > 32 * 1024 * 1024) {
                        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "DOCX 解压内容超过 32 MB 限制");
                    }
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw unsupported();
        }
        if (!entries.containsAll(Set.of("[Content_Types].xml", "_rels/.rels", "word/document.xml"))) {
            throw unsupported();
        }
    }

    private static ResponseStatusException unsupported() {
        return new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "文件类型不受支持或内容与扩展名不符");
    }

    public record StoredFile(String name, String key, String sha256, String mediaType, long size) {
    }
    public record FileEntry(String key, String storageStatus, Long sizeBytes) {}
    public record FilePage(boolean more, java.util.List<FileEntry> files) {}
}
