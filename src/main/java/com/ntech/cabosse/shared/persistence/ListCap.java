package com.ntech.cabosse.shared.persistence;

import org.jboss.logging.Logger;

import java.util.List;

/**
 * Plafond de sécurité pour les listes de <strong>navigation</strong> non
 * paginées (recherche/affichage). Borne le chargement mémoire d'une
 * collection tenant qui grossit, sans casser le contrat front actuel (qui
 * consomme la liste entière).
 *
 * <p>La troncature n'est jamais silencieuse : {@link #warnIfCapped} émet un
 * WARN quand le plafond est atteint — signal qu'il est temps d'introduire
 * une vraie pagination serveur sur l'endpoint concerné.</p>
 *
 * <p>À n'utiliser que sur des listes d'affichage. Les requêtes dont la
 * complétude est nécessaire (agrégats comptables, créances, reporting) ne
 * doivent <strong>pas</strong> être plafonnées.</p>
 */
public final class ListCap {

    /** Nombre maximum d'éléments retournés par une liste de navigation. */
    public static final int MAX = 1000;

    private static final Logger LOG = Logger.getLogger(ListCap.class);

    private ListCap() {}

    public static <T> List<T> warnIfCapped(List<T> rows, String label) {
        if (rows.size() >= MAX) {
            LOG.warnf("Liste « %s » plafonnée à %d éléments : pagination serveur recommandée.",
                    label, MAX);
        }
        return rows;
    }
}
