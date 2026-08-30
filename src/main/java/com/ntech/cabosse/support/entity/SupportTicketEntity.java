package com.ntech.cabosse.support.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Un ticket d'assistance.
 *
 * <p>Il vit dans le plan de contrôle et non dans la base de la structure :
 * un ticket appartient bien à une coopérative, mais l'éditeur doit pouvoir
 * les lister tous, les trier par priorité et les affecter. Les répartir
 * dans autant de bases que de structures rendrait cette vue impossible
 * sans balayer chaque base à chaque affichage.</p>
 *
 * <p>Le nom de la structure est recopié sur le ticket. La renommer plus
 * tard ne doit pas réécrire l'historique du support, et un ticket doit
 * rester lisible même si la structure a été supprimée.</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.SUPPORT_TICKETS)
public class SupportTicketEntity extends PanacheMongoEntityBase {

    @BsonId
    public UUID id;

    /** Référence lisible, de la forme TCK-2026-0001. */
    public String ref;

    public UUID tenantId;
    public String tenantName;

    public String subject;
    public String description;

    public TicketCategory category;
    public TicketPriority priority;
    public TicketStatus status;

    public String reportedBy;
    public String reportedByEmail;
    public UUID reportedByUserId;

    /** Agent de l'éditeur en charge, ou {@code null} si personne encore. */
    public String assignedTo;

    public Instant createdAt;
    public Instant updatedAt;
    public Instant closedAt;

    /** Fil chronologique, du plus ancien au plus récent. */
    public List<TicketMessageEntity> messages = new ArrayList<>();
}
