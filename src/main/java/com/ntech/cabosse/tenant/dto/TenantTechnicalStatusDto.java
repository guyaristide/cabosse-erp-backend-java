package com.ntech.cabosse.tenant.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Statut technique consolidé d'un tenant — vue diagnostic pour le
 * back-office plateforme (onglet "Technique" sur la fiche tenant).
 *
 * <p>Reflète l'état réel de la base MongoDB du tenant :
 * <ul>
 *   <li>statistiques globales ({@code dbStats}),</li>
 *   <li>statistiques par collection ({@code collStats}),</li>
 *   <li>historique Mongock ({@code mongockChangeLog} de la base tenant),</li>
 *   <li>historique backups ({@code cabosse_control.tenant_backups}).</li>
 * </ul>
 */
@Schema(description = "Statut technique d'un tenant (vue diagnostic)")
public record TenantTechnicalStatusDto(

        UUID tenantId,
        String databaseName,
        long databaseSizeBytes,
        int collectionsCount,

        @Schema(description = "Numéro de la dernière migration appliquée (ex. \"001\")")
        String mongockVersion,

        @Schema(enumeration = { "ok", "pending", "failed" })
        String migrationsHealth,

        Instant checkedAt,

        List<CollectionStatDto> collections,
        List<MigrationEntryDto> migrations,

        @Schema(enumeration = { "hourly", "daily", "weekly" })
        String backupFrequency,

        List<BackupSnapshotDto> recentBackups,

        /**
         * Consommation face aux plafonds du plan (backlog SAAS-02) : sans
         * cette lecture, un agent ne peut ni anticiper l'approche d'un
         * plafond ni proposer le palier supérieur avant le refus.
         */
        @Schema(description = "Comptes actifs ou invités consommant un siège du plan")
        long activeUsers,
        @Schema(description = "Plafond de comptes du plan. Nul : non contraint")
        int maxUsers,
        @Schema(description = "Producteurs membres enregistrés")
        long membersCount,
        @Schema(description = "Plafond de producteurs du plan. Nul : non contraint")
        int maxMembers

) {}
