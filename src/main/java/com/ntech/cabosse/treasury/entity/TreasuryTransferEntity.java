package com.ntech.cabosse.treasury.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Transport de fonds d'un compte de trésorerie à un autre, typiquement de
 * la banque vers la caisse. Tenant-scopé ({@code treasury_transfers}).
 *
 * <p>La ville de la coopérative n'a pas d'agence bancaire : les retraits
 * sont donc espacés, importants, et l'argent voyage physiquement. Deux
 * questions se posent alors, auxquelles aucun support papier ne répondait :
 * qui portait la somme, et est-ce que ce qui est entré en caisse
 * correspond à ce qui est sorti de banque.</p>
 *
 * <p>Comptablement, la somme transite par un compte de virements internes.
 * C'est ce qui permet à la sortie et à l'entrée d'être deux écritures
 * distinctes, datées chacune de son propre jour, sans que la trésorerie
 * paraisse dédoublée ou disparue entre les deux.</p>
 */
public class TreasuryTransferEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code TRF-YYYY-NNNN}. Unique par tenant. */
    public String ref;

    // ─── Origine ───
    public UUID fromAccountId;
    public String fromAccountLabel;
    public String fromSyscohadaAccount;

    // ─── Destination ───
    public UUID toAccountId;
    public String toAccountLabel;
    public String toSyscohadaAccount;

    public BigDecimal amountSent;
    public LocalDate sentAt;

    /**
     * Personne qui transporte les fonds. Ce n'est pas un utilisateur du
     * système : c'est souvent un chauffeur ou un agent, et c'est le nom
     * qu'on cherche quand une somme n'arrive pas.
     */
    public String carrierName;

    public TreasuryTransferStatus status = TreasuryTransferStatus.IN_TRANSIT;

    // ─── Réception ───
    public BigDecimal amountReceived;
    public LocalDate receivedAt;
    public String receivedByEmail;

    /**
     * Reçu moins envoyé. Négatif quand il manque de l'argent à l'arrivée.
     * Constaté comptablement à la réception pour que le compte de
     * virements internes se solde.
     */
    public BigDecimal discrepancy;

    /** Pièce de la sortie du compte d'origine. */
    public String pieceRefOut;
    /** Pièce de l'entrée au compte de destination. */
    public String pieceRefIn;

    public String notes;
    public Instant cancelledAt;
    public String cancellationReason;

    /**
     * Campagne de rattachement, déduite de {@link #sentAt}. Nulle quand aucune
     * campagne ne couvre la date et qu'aucune n'est ouverte.
     */
    public UUID campaignId;

    /** Année de la campagne, dénormalisée pour les regroupements. */
    public Integer campaignYear;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    public long version = 0L;

    public TreasuryTransferEntity() {}
}
