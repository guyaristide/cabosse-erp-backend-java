package com.ntech.cabosse.platform.transfer.service;

import java.util.List;

/**
 * La forme de l'archive de plateforme, partagée par l'export et la
 * restauration.
 *
 * <p>Distincte de l'archive d'une structure : celle-ci emporte le plan
 * de contrôle entier, toutes les bases de structures et tous les
 * binaires, sans filtrer sur quoi que ce soit. Elle sert à remonter un
 * serveur, pas à déplacer un client.</p>
 */
public final class PlatformArchiveLayout {

    private PlatformArchiveLayout() {}

    /** Version du format. À incrémenter dès que la disposition change. */
    public static final int FORMAT = 1;

    public static final String MANIFEST = "manifest.json";

    /** {@code control/<collection>.jsonl} */
    public static final String CONTROL_DIR = "control/";

    /** {@code tenants/<databaseName>/<collection>.jsonl} */
    public static final String TENANTS_DIR = "tenants/";

    /** {@code files/<fileId>} */
    public static final String FILES_DIR = "files/";

    /**
     * Ce qu'on laisse volontairement de côté.
     *
     * <p>Les jetons de rafraîchissement ne partent pas et ne reviennent
     * pas : une sauvegarde qui les rejoue rouvre des sessions fermées
     * depuis, éventuellement celles de comptes supprimés entre temps. La
     * restauration les efface donc, et tout le monde se reconnecte.</p>
     */
    public static final List<String> EXCLUDED = List.of(
            "refresh_tokens : les rejouer rouvrirait des sessions fermées depuis");
}
