package com.ntech.cabosse.health;

/**
 * Ce qui tourne, et depuis quand.
 *
 * @param application  nom de l'application
 * @param version      version du projet
 * @param commit       commit compilé, court, ou « inconnu »
 * @param branch       branche d'origine du build
 * @param builtAt      instant de compilation
 * @param startedAt    instant du dernier démarrage : c'est lui qui applique
 *                     les migrations en attente
 * @param migrations   état de la dernière passe de migrations
 */
public record VersionDto(
        String application,
        String version,
        String commit,
        String branch,
        String builtAt,
        String startedAt,
        Migrations migrations
) {

    /**
     * @param applied tenants migrés sans erreur au dernier démarrage
     * @param failed  tenants dont la chaîne s'est arrêtée : chacun d'eux
     *                ignore toutes les livraisons postérieures à la
     *                migration fautive
     */
    public record Migrations(int applied, int failed) {
    }
}
