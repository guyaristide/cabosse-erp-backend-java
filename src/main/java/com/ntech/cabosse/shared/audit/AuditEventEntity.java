package com.ntech.cabosse.shared.audit;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Une ligne du journal d'audit plateforme.
 *
 * <p>Convention :
 * <ul>
 *   <li>{@code eventType} stocké comme String (nom de l'enum) — résiste
 *       au renommage / ajout d'événements sans casser les anciens docs ;</li>
 *   <li>{@code actor*} = celui qui DÉCLENCHE l'action (super-admin lors
 *       d'un provisioning, user lors d'un login, etc.) ;</li>
 *   <li>{@code target*} = ce sur quoi l'action porte (tenant, user, plan,
 *       section paramètres…) ;</li>
 *   <li>{@code tenantId} = scope tenant si l'événement concerne un tenant
 *       précis ({@code null} pour les événements purement plateforme
 *       comme la modification d'un catalogue) ;</li>
 *   <li>{@code payload} = JSON arbitraire (diff de valeurs, raison d'échec,
 *       etc.) — pas indexé, sert d'inspection en debug.</li>
 * </ul>
 *
 * <p>Pas de mutation après écriture. Une entrée d'audit n'est jamais
 * modifiée ni supprimée par le code applicatif — un job de rétention
 * archive/purge éventuellement les très vieilles entrées (hors scope MVP).</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.GLOBAL_AUDIT)
public class AuditEventEntity extends PanacheMongoEntityBase {

    @BsonId
    public UUID id;

    public Instant occurredAt;

    /** Nom de l'enum {@link AuditEventType}. */
    public String eventType;

    /** Nom de l'enum {@link AuditCategory}. Dénormalisé pour faciliter les filtres. */
    public String category;

    // ─── Acteur ───
    public String actorEmail;
    public UUID actorUserId;
    /** Si super-admin impersonifié, l'admin original. */
    public UUID actorImpersonatedBy;

    // ─── Cible ───
    /** Type métier : {@code tenant}, {@code user}, {@code plan}, {@code country}, {@code settings.email}, etc. */
    public String targetType;
    /** Identifiant brut de la cible (UUID, code, slug). Stringifié pour homogénéité. */
    public String targetId;
    /** Libellé humain pré-calculé ({@code "Coopérative NOMMEE 1"}, {@code "anna.dorit@yopmail.com"}). */
    public String targetLabel;

    // ─── Scope tenant ───
    public UUID tenantId;
    public String tenantName;

    // ─── Contenu lisible ───
    /** Phrase descriptive prête à afficher (FR). */
    public String description;

    /** Détails arbitraires (diff, raison, contexte). Pas indexé. */
    public Map<String, Object> payload;

    /** Adresse IP du déclencheur, si disponible. */
    public String ipAddress;
}
