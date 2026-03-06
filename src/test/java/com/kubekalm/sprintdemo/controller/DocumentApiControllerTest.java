package com.kubekalm.sprintdemo.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kubekalm.sprintdemo.model.DocumentDownload;
import com.kubekalm.sprintdemo.model.DocumentItem;
import com.kubekalm.sprintdemo.service.S3DocumentStorageService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DocumentApiController.class)
class DocumentApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private S3DocumentStorageService storageService;

    @Test
    void listDocumentsReturnsItems() throws Exception {
        when(storageService.listDocuments()).thenReturn(List.of(
                new DocumentItem("abc", "manual.pdf", 1234L, Instant.parse("2026-03-06T00:00:00Z"))));

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("abc"))
                .andExpect(jsonPath("$.items[0].name").value("manual.pdf"));
    }

    @Test
    void uploadReturnsCreated() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "manual.pdf",
                "application/pdf",
                "sample".getBytes(StandardCharsets.UTF_8));
        when(storageService.uploadDocument(any())).thenReturn(
                new DocumentItem("up1", "manual.pdf", 6L, Instant.parse("2026-03-06T00:00:00Z")));

        mockMvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("up1"))
                .andExpect(jsonPath("$.name").value("manual.pdf"));
    }

    @Test
    void uploadWithInvalidFileReturnsBadRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "manual.pdf",
                "application/pdf",
                "sample".getBytes(StandardCharsets.UTF_8));
        when(storageService.uploadDocument(any())).thenThrow(new IllegalArgumentException("File size exceeds 5MB limit."));

        mockMvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UPLOAD"));
    }

    @Test
    void downloadReturnsFile() throws Exception {
        when(storageService.downloadDocument("doc1")).thenReturn(
                new DocumentDownload("manual.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/documents/doc1/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("content-type", "application/pdf"))
                .andExpect(header().string("content-disposition", org.hamcrest.Matchers.containsString("manual.pdf")))
                .andExpect(content().bytes("pdf".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        doNothing().when(storageService).deleteDocument("doc1");

        mockMvc.perform(delete("/api/documents/doc1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void listReturnsServiceUnavailableWhenStorageMissing() throws Exception {
        doThrow(new IllegalStateException("Object storage credentials are not configured for this app."))
                .when(storageService).listDocuments();

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
    }
}
