package com.ntech.cabosse.qualitygrade.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Seuil de qualité convenu par la structure sur un élément d'analyse.
 *
 * <p>Trois bornes par élément : ce qui est accepté, ce qui ouvre une
 * réfaction, ce qui fait rejeter. Elles étaient jusqu'ici écrites en dur
 * dans un écran, présentées comme la référence d'une filière et d'un pays
 * donnés, et <strong>contredites</strong> par deux seuils dormant dans les
 * préférences du tenant sans être ni lus ni exposés.</p>
 *
 * <p>Elles deviennent un référentiel : chaque filière a ses normes, et
 * une même filière n'a pas les mêmes selon le marché ou le contrat.</p>
 */
public class QualityNormEntity {

    @BsonId
    public UUID id;

    /**
     * Élément analysé, en code stable ({@code humidity},
     * {@code foreignMatter}, {@code moldy}…). Unique par tenant.
     */
    public String elementCode;

    /** Libellé affiché de l'élément. */
    public String label;

    /** Jusqu'à ce taux, la livraison est acceptée sans décote. */
    public BigDecimal acceptanceMaxPct;

    /**
     * Jusqu'à ce taux, une réfaction s'applique. Null quand l'élément ne
     * connaît pas de fourchette : au-delà du seuil d'acceptation, c'est
     * un refus.
     */
    public BigDecimal refactionMaxPct;

    /** Rang d'affichage, l'ordre alphabétique n'ayant aucun sens ici. */
    public int sortOrder;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public QualityNormEntity() {}
}
