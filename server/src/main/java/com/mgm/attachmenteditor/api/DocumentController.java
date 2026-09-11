package com.mgm.attachmenteditor.api;

import com.mgm.attachmenteditor.service.DocumentStorageService;
import com.mgm.attachmenteditor.service.DocumentStorageService.DocumentMeta;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

/**
 * Plain REST API for getting/uploading the .docx file, independent of the
 * WebDAV endpoint used by the desktop Word integration. Useful for the
 * Next.js client to show metadata, and as a fallback download/upload path.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final DocumentStorageService storage;

    public DocumentController(DocumentStorageService storage) {
        this.storage = storage;
    }

    @GetMapping
    public List<DocumentMeta> list() {
        return storage.list();
    }

    @GetMapping("/{filename}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String filename) throws IOException {
        requireExists(filename);
        DocumentMeta meta = storage.meta(filename);
        return ResponseEntity.ok()
                .contentType(DOCX)
                .contentLength(meta.size())
                .eTag(meta.etag())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(new InputStreamResource(storage.read(filename)));
    }

    @GetMapping("/{filename}/meta")
    public DocumentMeta meta(@PathVariable String filename) throws IOException {
        requireExists(filename);
        return storage.meta(filename);
    }

    @PostMapping("/{filename}")
    public DocumentMeta upload(@PathVariable String filename, @RequestParam("file") MultipartFile file)
            throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is empty");
        }
        storage.write(filename, file.getInputStream());
        return storage.meta(filename);
    }

    private void requireExists(String filename) {
        if (!storage.exists(filename)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such document: " + filename);
        }
    }
}
