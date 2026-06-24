package com.ntech.cabosse.sale.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Vente d'un (ou plusieurs) produit(s) fini(s) à un client. Tenant-scopée
 * (collection {@code sales}).
 *
 * <p>Cycle de vie : {@link SaleStatus#QUOTE} (devis, modifiable, pas
 * d'impact stock, pas de paiement) → {@link SaleStatus#CONFIRMED}
 * (vente actée, paiements possibles, pas encore livré) →
 * {@link SaleStatus#DELIVERED} (PF sortis du stock via OUT) →
 * éventuellement {@link SaleStatus#CANCELLED} (compensations selon
 * statut antérieur).</p>
 *
 * <p>Le {@code paymentStatus} est <strong>dérivé</strong> du remplissage
 * des {@code payments} et toujours recalculé serveur — jamais saisi.</p>
 */
public class SaleEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code FA-YYYY-NNNN}. Unique par tenant. */
    public String ref;

    public UUID siteId;
    public String siteName;
    public SaleChannel channel;

    // ─── Client (snapshots) ───
    public UUID customerId;
    public String customerCode;
    public String customerName;
    public String customerLegalName;

    /**
     * Canal de distribution du client figé au moment de la création de la
     * vente (valeur de {@code CustomerChannelType}, {@code null} si non
     * renseigné). Un changement ultérieur du canal côté fiche client ne
     * doit pas modifier le reporting historique : les ventes passées
     * conservent leur canal d'origine.
     */
    public String channelTypeSnapshot;

    public LocalDate saleDate;
    /** Échéance de paiement (B2B essentiellement). */
    public LocalDate dueDate;
    /** Date de remise effective (info, peut différer du markDelivered). */
    public LocalDate deliveryDate;

    public List<SaleLine> lines = new ArrayList<>();

    // ─── Totaux ───
    public BigDecimal subtotalHtFcfa;     // Σ lineTotalHt
    public BigDecimal discountPct;        // remise globale 0..100
    public BigDecimal discountFcfa;       // dérivée
    public BigDecimal vatRatePct;
    public BigDecimal vatFcfa;
    public BigDecimal totalTtcFcfa;       // HT - remise + TVA

    // ─── Coût & marge ───
    public BigDecimal totalCostFcfa;      // Σ(qty × cmupAtSale)
    public BigDecimal grossMarginFcfa;    // totalTtc - totalCost

    // ─── Paiements (embed) ───
    public List<SalePayment> payments = new ArrayList<>();
    public BigDecimal totalPaidFcfa = BigDecimal.ZERO;
    public PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    // ─── Statut macro + cancellation ───
    public SaleStatus status = SaleStatus.QUOTE;
    public SaleCancellation cancellation;

    /** Référence externe de la facture si générée ailleurs. */
    public String invoiceNumber;
    public String notes;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    /**
     * Lock optimiste — incrémenté à chaque écriture via
     * {@link com.ntech.cabosse.sale.repository.SaleRepository#replace}.
     * Ferme la race de {@code recordPayment} (lecture solde → écriture) :
     * deux paiements concurrents ne peuvent plus s'écraser silencieusement.
     */
    public long version = 0L;

    public SaleEntity() {}
}
