package com.ntech.cabosse.notification.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * La règle d'un événement de notification, décidée par l'administrateur
 * du tenant (CE-205) : l'événement déclenche-t-il encore, vers quels
 * profils, par quels canaux, avec quelles copies.
 *
 * <p>Un événement sans règle garde son comportement d'origine, décrit au
 * catalogue : les destinataires par droit, courriel et application. La
 * règle est l'exception posée par l'administrateur, pas une copie du
 * défaut.</p>
 */
public class NotificationRuleEntity {

    @BsonId
    public UUID id;

    /** Code du catalogue, unique par tenant (ex. collector-advance.pending-approval). */
    public String eventCode;

    /** {@code false} : l'événement ne déclenche plus rien. */
    public boolean enabled = true;

    /** Canaux retenus (noms de {@link NotificationChannel}), au moins un si actif. */
    public List<String> channels;

    /**
     * Profils destinataires. Null ou vide : l'audience par défaut du
     * catalogue (les porteurs du droit concerné) reste en vigueur.
     */
    public List<UUID> recipientRoleIds;

    /** Adresses en copie, prévenues par courriel dans la langue de la structure. */
    public List<String> ccEmails;

    public Instant createdAt;
    public Instant updatedAt;
    public String updatedByEmail;
}
