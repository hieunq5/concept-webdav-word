package com.mgm.attachmenteditor.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads/writes .docx files from a plain external directory (not the classpath,
 * which is read-only once packaged). Seeds the directory with a sample file on
 * first startup so both the REST API and the WebDAV endpoint have something to
 * serve immediately.
 */
@Service
public class DocumentStorageService implements ApplicationRunner {

    private static final Pattern SAFE_FILENAME = Pattern.compile("^[A-Za-z0-9._-]+\\.docx$");

    private final Path documentsDir;
    private final String defaultFile;

    public DocumentStorageService(
            @Value("${app.documents.dir}") String documentsDir,
            @Value("${app.documents.default-file}") String defaultFile) {
        this.documentsDir = Path.of(documentsDir).toAbsolutePath().normalize();
        this.defaultFile = defaultFile;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Files.createDirectories(documentsDir);
        Path seeded = documentsDir.resolve(defaultFile);
        if (Files.notExists(seeded)) {
            ClassPathResource seed = new ClassPathResource("documents/" + defaultFile);
            try (InputStream in = seed.getInputStream()) {
                Files.copy(in, seeded, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public String defaultFile() {
        return defaultFile;
    }

    public Path resolve(String filename) {
        if (!SAFE_FILENAME.matcher(filename).matches()) {
            throw new IllegalArgumentException("Invalid file name: " + filename);
        }
        return documentsDir.resolve(filename).normalize();
    }

    public boolean exists(String filename) {
        return Files.exists(resolve(filename));
    }

    public InputStream read(String filename) throws IOException {
        return Files.newInputStream(resolve(filename));
    }

    public void write(String filename, InputStream content) throws IOException {
        Path target = resolve(filename);
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "upload-", ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            content.transferTo(out);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public DocumentMeta meta(String filename) throws IOException {
        Path path = resolve(filename);
        long size = Files.size(path);
        Instant modified = Files.getLastModifiedTime(path).toInstant();
        String etag = "\"" + Long.toHexString(modified.toEpochMilli()) + "-" + Long.toHexString(size) + "\"";
        return new DocumentMeta(filename, size, modified, etag);
    }

    public List<DocumentMeta> list() {
        List<DocumentMeta> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(documentsDir, "*.docx")) {
            for (Path p : stream) {
                result.add(meta(p.getFileName().toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    public record DocumentMeta(String filename, long size, Instant lastModified, String etag) {
    }
}
