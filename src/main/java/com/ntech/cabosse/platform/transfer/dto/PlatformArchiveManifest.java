package com.ntech.cabosse.platform.transfer.dto;

import java.time.Instant;
import java.util.List;

/**
 * Ce que l'archive de plateforme dit d'elle-même.
 *
 * <p>Elle nomme chaque structure emportée et sa base : une restauration
 * doit pouvoir annoncer ce qu'elle va remplacer <strong>avant</strong>
 * de le remplacer. Et {@link #migrationVersion} permet le même refus que
 * pour une structure seule : une archive prise après une migration que
 * ce serveur ne connaît pas produirait des lectures fausses sans erreur.</p>
 */
public record PlatformArchiveManifest(

        /** Version du format d'archive, pour que la lecture sache quoi attendre. */
        int archiveFormat,

        Instant exportedAt,
        String exportedByEmail,

        /** Version de l'application qui a produit l'archive. */
        String applicationVersion,
        String applicationCommit,

        /** Dernière migration appliquée au plan de contrôle. */
        String migrationVersion,

        /** Collections du plan de contrôle, avec leur nombre de documents. */
        List<CollectionCount> controlPlane,

        /** Une entrée par structure emportée. */
        List<TenantSlice> tenants,

        long filesCount,
        long filesTotalBytes,

        /** Ce qui a été laissé de côté, et pourquoi. */
        List<String> excluded) {

    /** Une collection et ce qu'elle pesait au moment de l'export. */
    public record CollectionCount(String name, long documents) {}

    /** Une structure emportée, sa base et son contenu. */
    public record TenantSlice(String tenantId, String slug, String name,
                              String databaseName, List<CollectionCount> collections) {}
}
