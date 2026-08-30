package com.ntech.cabosse.support.entity;

import java.util.List;
import java.util.Map;

/**
 * Cycle de vie d'un ticket d'assistance.
 *
 * <p>Le graphe de transitions est celui que l'interface applique déjà :
 * le décrire ici, côté serveur, évite qu'un appel direct à l'API fasse
 * ce que l'écran refuse.</p>
 */
public enum TicketStatus {

    /** Ouvert par la structure, personne ne s'en est encore saisi. */
    OPEN,

    /** Pris en charge par l'éditeur. */
    IN_PROGRESS,

    /** En attente d'un retour de la structure. */
    WAITING,

    /** Traité, en attente de confirmation. */
    RESOLVED,

    /** Clos. Se rouvre si la structure revient dessus. */
    CLOSED;

    private static final Map<TicketStatus, List<TicketStatus>> ALLOWED = Map.of(
            OPEN, List.of(IN_PROGRESS, WAITING, RESOLVED),
            IN_PROGRESS, List.of(WAITING, RESOLVED),
            WAITING, List.of(IN_PROGRESS, RESOLVED),
            RESOLVED, List.of(IN_PROGRESS, CLOSED),
            // Un ticket clos se rouvre : une question qu'on croyait réglée
            // revient parfois, et rouvrir vaut mieux qu'un doublon qui perd
            // tout l'historique de l'échange.
            CLOSED, List.of(IN_PROGRESS));

    public boolean canMoveTo(TicketStatus next) {
        return next != null && ALLOWED.getOrDefault(this, List.of()).contains(next);
    }

    /** Clé du libellé, pour les courriels adressés à la structure. */
    public String messageKey() {
        return "m.tck-status-" + name().toLowerCase().replace('_', '-');
    }
}
