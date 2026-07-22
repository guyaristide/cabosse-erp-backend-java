package com.ntech.cabosse.cacao.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Contrat de vente de cacao (backlog NEG-02). Tenant-scopé (collection
 * {@code sales_contracts}). Défini par client et par campagne, il porte la
 * <strong>marge</strong> négociée (FCFA/kg) et, si le contrat est certifié,
 * les <strong>taux de primes</strong> (coopérative / producteur / actions
 * sociales) du label.
 *
 * <p>Le prix de vente d'une expédition en découle : prix bord champ de la
 * campagne ({@code CampaignEntity.basePricePerKgFcfa}) + {@link #marginPerKgFcfa},
 * primes selon le label. Le contrat <strong>pré-remplit</strong> la vente,
 * qui reste surchargeable (décision « hybride » du 22/07/2026).</p>
 */
public class SalesContractEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code CTR-YYYY-NNNN}. Unique par tenant. */
    public String ref;

    /** Client (référentiel Clients existant). */
    public UUID customerId;
    public String customerName;

    public UUID campaignId;
    public Integer campaignYear;

    /** Marge négociée ajoutée au prix bord champ (FCFA/kg). */
    public BigDecimal marginPerKgFcfa = BigDecimal.ZERO;

    /** Label de certification du contrat (ex. {@code "RA"}, {@code "FT"}), ou null. */
    public String label;

    /** Prime label revenant à la coopérative (FCFA/kg). */
    public BigDecimal coopPrimePerKgFcfa = BigDecimal.ZERO;
    /** Prime label destinée aux producteurs (FCFA/kg). */
    public BigDecimal producerPrimePerKgFcfa = BigDecimal.ZERO;
    /** Prime label destinée aux actions sociales (FCFA/kg). */
    public BigDecimal socialPrimePerKgFcfa = BigDecimal.ZERO;

    public String notes;
    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    public SalesContractEntity() {}
}
