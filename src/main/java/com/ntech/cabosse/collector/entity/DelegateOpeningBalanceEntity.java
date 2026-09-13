package com.ntech.cabosse.collector.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Ce qu'un délégué devait à la coopérative à l'ouverture d'une campagne,
 * pour ce que l'outil n'a pas vu.
 *
 * <p>Demandé par l'expert-comptable le 12/09/2026. Le report d'une
 * campagne à la suivante se calcule déjà tout seul à partir des avances,
 * des livraisons et des règlements enregistrés. Mais la première campagne
 * portée par l'outil n'a rien derrière elle : des délégués arrivaient
 * débiteurs de la campagne précédente et leur compte démarrait à
 * zéro.</p>
 *
 * <p>Positif, le délégué doit à la coopérative. Négatif, c'est elle qui
 * lui doit. Un seul montant par délégué et par campagne.</p>
 */
public class DelegateOpeningBalanceEntity {

    public static final String COLLECTION = "delegate_opening_balances";

    @BsonId
    public UUID id;

    public UUID delegateSupplierId;
    /** Dénormalisé pour que l'état reste lisible si la fiche change de nom. */
    public String delegateName;

    public UUID campaignId;
    public Integer campaignYear;

    /** Positif : le délégué doit. Négatif : la coopérative lui doit. */
    public BigDecimal amount;

    /** D'où vient le chiffre : le dire évite d'avoir à le redemander. */
    public String notes;

    public Instant createdAt;
    public String createdByEmail;
    public Instant updatedAt;
    public String updatedByEmail;
}
