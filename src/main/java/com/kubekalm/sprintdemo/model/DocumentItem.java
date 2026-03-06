package com.kubekalm.sprintdemo.model;

import java.time.Instant;

public record DocumentItem(
        String id,
        String name,
        long size,
        Instant uploadedAt
) {
}
