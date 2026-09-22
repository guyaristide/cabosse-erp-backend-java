package com.ntech.cabosse.tenant.transfer.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ce que l'archive dit d'elle-même (backlog SAAS-20).
 *
 * <p>Une archive sans manifeste est un tas de fichiers : on ne sait ni
 * d'où elle vient, ni jusqu'où le schéma était migré quand elle a été
 * prise. Restaurée sur un serveur plus ancien, elle produirait des
 * documents que le code ne sait pas lire, <strong>sans rien dire</strong>.
 * D'où {@link #migrationVersion}, que la restauration compare avant de
 * toucher quoi que ce soit.</p>
 *
 * <p>{@link #excluded} nomme ce qui a été volontairement laissé de côté :
 * une archive qui se tait sur ses trous laisse croire qu'elle est
 * complète.</p>
 */
public record TenantArchiveManifest(

        /** Version du format d'archive, pour que la lecture sache quoi attendre. */
        int archiveFormat,

        UUID tenantId,
        String tenantSlug,
        String tenantName,
        String databaseName,

        Instant exportedAt,
        String exportedByEmail,

        /** Version de l'application qui a produit l'archive. */
        String applicationVersion,
        String applicationCommit,

        /** Dernière migration appliquée à la base du tenant. */
        String migrationVersion,

        /** Collections de la base du tenant, avec leur nombre de documents. */
        List<CollectionCount> tenantCollections,

        /** Documents du plan de contrôle emportés, par collection. */
        List<CollectionCount> controlPlaneSlices,

        long filesCount,
        long filesTotalBytes,

        /** Ce qui a été laissé de côté, et pourquoi. */
        List<String> excluded) {

    /** Une collection et ce qu'elle pesait au moment de l'export. */
    public record CollectionCount(String name, long documents) {}
}
