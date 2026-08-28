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
 * {@code CMP-YYYY-NN} (ex : {@code CMP-2026-01}). Une saison se joue en
 * plusieurs campagnes : principale puis intermédiaire, chacune avec sa
 * période et son prix bord champ fixé en début de campagne. Elles sont
 * ouvertes en même temps : la principale n'est pas close le jour où
 * l'intermédiaire démarre. La campagne « courante » est celle dont la
 * période couvre le jour, pas la seule ouverte.</p>
 */
public class CampaignEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code CMP-YYYY-NN}. Unique par tenant. */
    public String code;

    /** Libellé court ({@code "Campagne principale 2026"}). */
    public String label;

    /**
     * Année agricole de référence, <strong>déduite de {@link #startDate}
     * et jamais saisie</strong>. Sert de clé de tri dénormalisée sur les
     * flux rattachés à la campagne ; elle n'identifie rien à elle seule,
     * plusieurs campagnes pouvant démarrer la même année.
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
     *
     * <p><strong>Verrouillé.</strong> Il ne se modifie pas par la mise à
     * jour ordinaire de la campagne mais par un geste dédié, réservé au
     * droit {@code CAMPAIGN_PRICE_WRITE} et motivé. C'est le prix payé au
     * producteur : le laisser librement éditable ouvrait la porte à un
     * changement discret entre deux pesées.</p>
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

    /**
     * Historique des changements de barème, du plus ancien au plus récent.
     *
     * <p>Un prix qui change sans trace ne se conteste pas : chaque
     * modification garde le barème d'avant, celui d'après, son motif et son
     * auteur. C'est ce qui permet, une campagne finie, de rapprocher un reçu
     * du prix en vigueur le jour où il a été établi.</p>
     */
    public List<TariffChange> tariffHistory = new ArrayList<>();

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    public CampaignEntity() {}
}
