package com.ntech.cabosse.stock.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Session d'inventaire physique ({@code inventory_sessions}).
 *
 * <p>Cycle : {@code OPEN} (théorique figé à l'ouverture, saisie des
 * comptages) → {@code SUBMITTED} (comptage terminé, en attente de
 * validation) → {@code VALIDATED} (ajustements appliqués au stock +
 * pièce comptable de régularisation). {@code CANCELLED} possible tant
 * que la session n'est pas validée.</p>
 *
 * <p>Le théorique et le CMUP de chaque ligne sont <strong>figés à
 * l'ouverture</strong> : l'écart affiché et valorisé correspond à l'état
 * du stock au lancement du comptage. Les mouvements passés entre
 * l'ouverture et la validation ne sont pas neutralisés — l'inventaire
 * doit être conduit sur une fenêtre calme (pas de réception ni de
 * sortie pendant le comptage).</p>
 */
public class InventorySessionEntity {

    @BsonId
    public UUID id;

    /** Référence lisible {@code INV-YYYY-NNNN}. */
    public String ref;

    public UUID siteId;
    public String siteName;

    /** OPEN, SUBMITTED, VALIDATED, CANCELLED. */
    public String status;

    /** Motif de l'inventaire (obligatoire, repris sur les ajustements). */
    public String reason;

    public List<Line> lines;

    /**
     * Campagne de rattachement, déduite de {@link #openedAt}. Nulle quand aucune
     * campagne ne couvre la date et qu'aucune n'est ouverte.
     */
    public UUID campaignId;

    /** Année de la campagne, dénormalisée pour les regroupements. */
    public Integer campaignYear;

    public Instant openedAt;
    public UUID openedBy;
    public Instant submittedAt;
    public UUID submittedBy;
    public Instant validatedAt;
    public UUID validatedBy;
    public Instant cancelledAt;
    public UUID cancelledBy;

    /** Référence de la pièce comptable de régularisation, une fois validée. */
    public String pieceRef;

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_VALIDATED = "VALIDATED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** Ligne d'inventaire — snapshot article + théorique figé + compté. */
    public static class Line {
        public UUID articleId;
        public String articleCode;
        public String articleName;
        public String articleUnit;
        /** Nom d'une valeur {@code ArticleType}. */
        public String articleType;
        /** Quantité théorique figée à l'ouverture de la session. */
        public BigDecimal theoreticalQty;
        /** CMUP figé à l'ouverture — sert à valoriser l'écart. */
        public BigDecimal cmup;
        /** Quantité comptée ; {@code null} tant que non saisie. */
        public BigDecimal countedQty;
        public String notes;
    }
}
