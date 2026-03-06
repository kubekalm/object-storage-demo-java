package com.kubekalm.sprintdemo.model;

public record DocumentDownload(
        String fileName,
        String contentType,
        byte[] bytes
) {
}
