package com.ntech.cabosse.campaign.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Campagne de rémunération des membres-producteurs. Tenant-scopée
 * (collection {@code campaigns}). Disponible si la capacité
 * {@link com.ntech.cabosse.tenant.capability.TenantCapability#HAS_MEMBERS}
 * est active.
 *
 * <p>Une campagne porte la grille tarifaire qui sera appliquée aux
 * livraisons des membres pour la période courante : prix de base par kg
 * de matière brute (ex : fèves fraîches cacao), primes qualité par
 * grade, ristourne en pourcentage du total. Le calcul de la rémunération
 * vit dans {@code MemberPayoutService} (livré au ticket 4.2).</p>
 *
 * <p>La référence affichable {@link #code} est de la forme
 * {@code CMP-YYYY-NN} (ex : {@code CMP-2026-01}). Plusieurs campagnes
 * peuvent exister pour la même année (mi-saison, fin de saison) mais
 * une seule peut être {@link CampaignStatus#OPEN} à un instant donné
 * — contrainte applicative validée par {@code CampaignService}.</p>
 */
public class CampaignEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code CMP-YYYY-NN}. Unique par tenant. */
    public String code;

    /** Libellé court ({@code "Campagne principale 2026"}). */
    public String label;

    /**
     * Année agricole de référence. Sert au regroupement et à
     * l'identification rapide de la campagne courante. Plusieurs
     * campagnes possibles pour une même année (intermédiaire, principale).
     */
    public int campaignYear;

    /** Date d'ouverture de la campagne (inclusive). */
    public LocalDate startDate;

    /**
     * Date de clôture théorique (inclusive). La clôture effective est
     * portée par {@link #closedAt} qui peut différer.
     */
    public LocalDate endDate;

    /**
     * Prix de base par kg de matière brute livrée, en devise tenant.
     * S'applique avant primes qualité et ristourne.
     */
    public BigDecimal basePricePerKgFcfa = BigDecimal.ZERO;

    /**
     * Primes qualité par grade de fèves. Peut être vide si la coopérative
     * n'applique pas de différenciation qualité. Une entrée par grade
     * attendu — l'absence d'une entrée pour un grade signifie « prime
     * nulle » au calcul (pas une erreur).
     */
    public List<QualityPremium> qualityPremiums = new ArrayList<>();

    /**
     * Ristourne en pourcentage du montant total rémunéré (base + primes),
     * appliquée à la clôture ou sur décision du conseil. Optionnelle.
     */
    public BigDecimal ristournePct = BigDecimal.ZERO;

    /**
     * Mode de paiement par défaut applicable à la campagne (espèces,
     * mobile money, virement). Saisie libre au MVP. Le membre peut avoir
     * son propre {@code preferredPaymentMethod} qui prime.
     */
    public String defaultPaymentMethod;

    public String notes;

    public CampaignStatus status = CampaignStatus.OPEN;

    /** Instant effectif de clôture (renseigné lors du passage en CLOSED). */
    public Instant closedAt;
    public UUID closedBy;
    public String closedByEmail;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    public CampaignEntity() {}
}
