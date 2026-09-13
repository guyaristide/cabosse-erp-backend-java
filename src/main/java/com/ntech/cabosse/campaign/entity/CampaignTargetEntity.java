package com.ntech.cabosse.campaign.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * L'objectif d'un mois de campagne, en collecte et en vente.
 *
 * <p>Demandé par la coopérative le 13/09/2026 : ses tableaux de bord
 * confrontent chaque mois le réalisé à un objectif, et l'écart. Le
 * réalisé se calcule déjà ; l'objectif, lui, est une décision, il ne se
 * déduit de rien et doit se saisir.</p>
 *
 * <p>Un document par mois et par campagne. Un mois sans objectif n'est
 * pas un objectif à zéro : l'écart ne se calcule alors pas, plutôt que
 * d'afficher un retard qui n'a jamais été décidé.</p>
 */
public class CampaignTargetEntity {

    public static final String COLLECTION = "campaign_targets";

    @BsonId
    public UUID id;

    public UUID campaignId;

    /** Le mois visé, au format « AAAA-MM » : trié comme il se lit. */
    public String month;

    /** Objectif de collecte du mois, dans l'unité de la matière. */
    public BigDecimal collectionTargetKg;

    /** Objectif de vente du mois. */
    public BigDecimal saleTargetKg;

    public Instant updatedAt;
    public String updatedByEmail;
}
