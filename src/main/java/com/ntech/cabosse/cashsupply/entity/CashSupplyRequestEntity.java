package com.ntech.cabosse.cashsupply.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Demande d'approvisionnement de la caisse. Tenant-scopé
 * ({@code cash_supply_requests}).
 *
 * <p>La caisse se vide au fil des achats bord champ et il faut aller la
 * remplir à la banque. Le geste existait déjà, sous la forme d'un
 * transport de fonds que la caissière saisissait seule : rien ne disait
 * qui l'avait décidé. La direction demande donc, la gouvernance accorde,
 * la caisse exécute (demandé le 03/10/2026).</p>
 *
 * <p>La demande ne touche à aucun compte. Elle autorise une sortie de
 * banque ; c'est le transport de fonds qu'elle engendre qui porte les
 * écritures, comme n'importe quel autre retrait. Lui faire passer les
 * écritures aussi compterait l'argent deux fois.</p>
 */
public class CashSupplyRequestEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code DAC-YYYY-NNNN}. Unique par tenant. */
    public String ref;

    // ─── La caisse à remplir ───
    public UUID cashAccountId;
    public String cashAccountLabel;

    /**
     * Banque sur laquelle tirer, quand la direction a une préférence.
     * Facultative : la caissière sait mieux qu'elle ce qui est
     * disponible le jour du retrait, et c'est elle qui tranche à
     * l'exécution.
     */
    public UUID bankAccountId;
    public String bankAccountLabel;

    public BigDecimal requestedAmount;

    /**
     * Ce qui justifie la demande. Obligatoire : la décision se prend sur
     * une raison, et un montant seul n'en est pas une.
     */
    public String reason;

    /** Date à laquelle la caisse en a besoin. */
    public LocalDate neededBy;

    public CashSupplyStatus status = CashSupplyStatus.PENDING_APPROVAL;

    // ─── Décision ───
    /**
     * Montant accordé, quand il diffère du sollicité. C'est lui qui
     * pilote l'aval : la caissière prépare le chèque sur ce montant.
     */
    public BigDecimal approvedAmount;
    public String approvalNote;
    public Instant approvedAt;
    public UUID approvedBy;
    public String approvedByEmail;

    public String rejectionReason;
    public Instant rejectedAt;
    public String rejectedByEmail;

    // ─── Exécution ───
    /** Numéro du chèque préparé pour le retrait. */
    public String chequeNumber;
    public Instant fulfilledAt;
    public String fulfilledByEmail;

    /** Transport de fonds engendré, qui porte les écritures. */
    public UUID transferId;
    public String transferRef;

    public Instant cancelledAt;
    public String cancellationReason;

    public LocalDate requestedOn;
    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    public long version = 0L;

    public CashSupplyRequestEntity() {}

    /** Le montant qui vaut : accordé s'il existe, sollicité sinon. */
    public BigDecimal effectiveAmount() {
        return approvedAmount != null ? approvedAmount : requestedAmount;
    }
}
