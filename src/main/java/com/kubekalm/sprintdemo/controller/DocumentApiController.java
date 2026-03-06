package com.kubekalm.sprintdemo.controller;

import com.kubekalm.sprintdemo.model.DocumentDownload;
import com.kubekalm.sprintdemo.model.DocumentItem;
import com.kubekalm.sprintdemo.service.S3DocumentStorageService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class DocumentApiController {
    private static final Logger logger = LoggerFactory.getLogger(DocumentApiController.class);

    private final S3DocumentStorageService storageService;

    public DocumentApiController(S3DocumentStorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "timestamp", Instant.now().toString(),
                "storageReady", storageService.isConfigured());
    }

    @GetMapping("/documents")
    public ResponseEntity<?> listDocuments() {
        try {
            List<DocumentItem> items = storageService.listDocuments();
            return ResponseEntity.ok(Map.of("items", items));
        } catch (IllegalStateException ex) {
            return apiError(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", ex.getMessage());
        } catch (Exception ex) {
            logger.error("Document listing failed", ex);
            return apiError(HttpStatus.INTERNAL_SERVER_ERROR, "LIST_FAILED", "Unable to load documents right now.");
        }
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadDocument(@RequestPart("file") MultipartFile file) {
        try {
            DocumentItem item = storageService.uploadDocument(file);
            return ResponseEntity.status(HttpStatus.CREATED).body(item);
        } catch (IllegalArgumentException ex) {
            return apiError(HttpStatus.BAD_REQUEST, "INVALID_UPLOAD", ex.getMessage());
        } catch (IllegalStateException ex) {
            return apiError(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", ex.getMessage());
        } catch (Exception ex) {
            logger.error("Document upload failed", ex);
            return apiError(HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_FAILED", "Unable to upload this document.");
        }
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<?> downloadDocument(@PathVariable String id) {
        try {
            DocumentDownload result = storageService.downloadDocument(id);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(result.fileName(), StandardCharsets.UTF_8)
                            .build().toString())
                    .contentType(MediaType.parseMediaType(result.contentType()))
                    .body(result.bytes());
        } catch (IllegalArgumentException ex) {
            return apiError(HttpStatus.BAD_REQUEST, "INVALID_ID", ex.getMessage());
        } catch (IllegalStateException ex) {
            return apiError(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", ex.getMessage());
        } catch (Exception ex) {
            logger.error("Document download failed", ex);
            return apiError(HttpStatus.INTERNAL_SERVER_ERROR, "DOWNLOAD_FAILED", "Unable to download this document.");
        }
    }

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<?> deleteDocument(@PathVariable String id) {
        try {
            storageService.deleteDocument(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            return apiError(HttpStatus.BAD_REQUEST, "INVALID_ID", ex.getMessage());
        } catch (IllegalStateException ex) {
            return apiError(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", ex.getMessage());
        } catch (Exception ex) {
            logger.error("Document delete failed", ex);
            return apiError(HttpStatus.INTERNAL_SERVER_ERROR, "DELETE_FAILED", "Unable to delete this document.");
        }
    }

    private ResponseEntity<Map<String, String>> apiError(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
