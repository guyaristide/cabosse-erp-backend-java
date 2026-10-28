package com.ntech.cabosse.shared.trace;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * La trace d'une requête HTTP, dans la base de la structure
 * (collection {@code request_traces}, SEC-16).
 *
 * <p>Le journal d'audit dit ce qui a été décidé ; la trace dit qui a
 * appelé quoi, quand, et ce que le serveur a répondu. Elle n'est écrite
 * que pour les écritures et les lectures sensibles (exports, pièces
 * jointes, archives), jamais pour les lectures ordinaires, et ne porte
 * <strong>jamais</strong> un corps de requête ni de réponse.</p>
 *
 * <p>Une trace est un constat : rien ne la relit dans un calcul, et son
 * absence ne change rien à l'opération tracée.</p>
 */
public class RequestTraceEntity {

    public static final String COLLECTION = "request_traces";

    @BsonId
    public UUID id;

    public Instant occurredAt;

    public String method;
    public String path;

    /** Paramètres de requête, sans ceux qui ressemblent à un secret. */
    public String query;

    public int status;
    public long durationMs;

    public String actorEmail;
    public UUID actorUserId;
    public UUID impersonatedBy;

    public String ipAddress;
    public String userAgent;

    /** Clé d'idempotence présentée, pour rapprocher un rejeu de son original. */
    public String idempotencyKey;

    /** Identifiant de corrélation, rendu dans {@code X-Request-Id} et présent dans les logs. */
    public String requestId;

    /** Taille annoncée de la réponse, quand le serveur la connaît. */
    public Long responseBytes;

    /** {@code OK}, {@code DENIED}, {@code REJECTED} ou {@code FAILED}. */
    public String outcome;

    public RequestTraceEntity() {}
}
