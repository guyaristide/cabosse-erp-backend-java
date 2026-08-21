package com.ntech.cabosse.notification.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Une ligne de la file d'envoi, dans la base du tenant (les destinataires
 * appartiennent au tenant). C'est elle qui rend une notification durable :
 * un redémarrage entre le fait métier et l'envoi ne perd rien, et le
 * journal permet de répondre « ce message est-il parti, et sinon pourquoi ».
 *
 * <p>Un seul chemin d'écriture alimente cette collection
 * ({@code NotificationQueue}). L'incident d'outbox observé sur un projet
 * voisin venait d'un second chemin d'écriture, pas du principe.</p>
 */
public class NotificationDeliveryEntity {

    @BsonId
    public UUID id;

    public NotificationChannel channel;
    public NotificationUsage usage;

    /** Adresse, numéro ou jeton d'appareil selon le canal. */
    public String target;

    /** Sujet rendu (courriel uniquement ; null ailleurs). */
    public String subject;

    /** Corps rendu. Le rendu a lieu à l'enfilage, pas à l'envoi. */
    public String body;

    /**
     * Code du fait métier à l'origine de l'envoi. Sert au filtrage du
     * journal et, plus tard, aux préférences par type d'événement.
     */
    public String eventType;

    /**
     * Référence de l'objet métier concerné ({@code sale:FA-2026-0012}).
     * Jamais une URL : un lien figé dans une ligne ancienne pointerait
     * vers une page qui a pu disparaître.
     */
    public String subjectRef;

    /** Langue de rendu retenue au moment de l'enfilage. */
    public String locale;

    public DeliveryStatus status;

    /** Nombre de prises effectuées. Le plafond est une constante unique. */
    public int attempts;

    /**
     * Date à partir de laquelle la ligne redevient éligible. Portée par la
     * ligne pour que le retrait progressif après échec ne dépende pas de la
     * cadence du relais.
     */
    public Instant nextAttemptAt;

    /**
     * Date au-delà de laquelle la ligne ne doit plus partir. Sans elle,
     * une file rallumée après une panne déverserait des alertes périmées.
     */
    public Instant expiresAt;

    /** Marque de prise : sert à reprendre une ligne dont l'envoyeur est mort. */
    public Instant claimedAt;

    /** Code du moteur qui a effectivement traité la ligne. */
    public String providerCode;

    /** Identifiant rendu par l'opérateur, pour le rapprochement. */
    public String providerMessageId;

    /**
     * Motif d'échec rendu par l'opérateur, tronqué. Conservé tel quel :
     * reformuler un message d'opérateur fait perdre l'information qui
     * permet de diagnostiquer.
     */
    public String lastError;

    public Instant createdAt;
    public Instant updatedAt;
    public Instant sentAt;

    public NotificationDeliveryEntity() {}
}
