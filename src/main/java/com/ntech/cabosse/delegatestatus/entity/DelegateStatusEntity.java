package com.ntech.cabosse.delegatestatus.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Position que la coopérative peut tenir sur un délégué (backlog DEL-02).
 *
 * <p>Référentiel tenant, éditable. Deux valeurs sont semées à l'ouverture,
 * « Délégué principal » et « Délégué douteux », parce que ce sont celles
 * que la coopérative a demandées ; elles ne sont pas figées dans le code
 * pour autant. Une structure qui voudra distinguer « en relance » de
 * « recouvrement clos » n'aura pas à attendre une livraison.</p>
 *
 * <p>Une position ne se supprime pas une fois utilisée : elle se
 * désactive. L'historique d'un délégué cite des positions passées, et une
 * suppression rendrait ces lignes illisibles.</p>
 */
public class DelegateStatusEntity {

    @BsonId
    public UUID id;

    /** Code stable, utilisé par les migrations et les tests. */
    public String code;

    /** Libellé affiché, modifiable par la structure. */
    public String label;

    /**
     * Signale la position qui vaut avertissement, pour que l'écran sache
     * laquelle mettre en évidence sans connaître les libellés du tenant.
     */
    public boolean warning = false;

    /** Ordre d'affichage dans les sélecteurs et les filtres. */
    public int sortOrder;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
}
