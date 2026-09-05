package com.ntech.cabosse.dispatch.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bordereau de sortie (épic magasin, CE-195, modèle à main levée de
 * l'expert du 05/09/2026). Tenant-scopé (collection {@code dispatch_notes}).
 *
 * <p>Le document du chargement d'un camion vers le client : une livraison
 * = un bordereau. Il se compose en appelant des reçus d'achat (les « BR »
 * du carnet), au besoin partiellement, le reliquat d'un reçu servant au
 * chargement suivant. Chaque ligne sort du stock au CMUP avec le lot du
 * reçu ; la vente appellera le bordereau (CE-194) et n'aura plus de stock
 * à sortir.</p>
 */
public class DispatchNoteEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code BS-YYYY-NNNN}. */
    public String ref;

    public LocalDate date;

    public UUID siteId;
    public String siteName;

    // ─── Article chargé (celui des reçus appelés) ───
    public UUID articleId;
    public String articleCode;
    public String articleName;
    public String articleUnit;

    /** Client destinataire, quand il est connu au chargement. */
    public UUID customerId;
    public String customerName;

    /** Camion, comme sur le bordereau de réception. */
    public String truckNumber;

    public UUID campaignId;
    public Integer campaignYear;

    public List<DispatchLine> lines;

    public BigDecimal totalGrossKg;
    public Integer totalBags;
    public BigDecimal totalNetKg;

    public DispatchNoteStatus status = DispatchNoteStatus.OPEN;

    /** La vente qui a appelé ce bordereau, une fois vendue. */
    public UUID saleId;
    public String saleRef;

    public String notes;

    // ─── Annulation ───
    public String cancellationReason;
    public Instant cancelledAt;
    public String cancelledByEmail;

    public Instant createdAt;
    public Instant updatedAt;
    public String createdByEmail;
    public long version = 0L;

    public DispatchNoteEntity() {}
}
