package com.ntech.cabosse.tenant.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

/**
 * Snapshot de backup d'un tenant. Lu depuis
 * {@code cabosse_control.tenant_backups} — collection alimentée par le
 * job mongodump (Phase D, pas encore implémenté).
 */
@Schema(description = "Snapshot de backup d'un tenant")
public record BackupSnapshotDto(
        String id,
        Instant executedAt,
        @Schema(enumeration = { "success", "failed", "pending" })
        String status,
        long sizeBytes,
        @Schema(description = "Code du plan tarifaire actif au moment du backup")
        String planAtTime,
        long durationMs,
        String errorMessage
) {}
