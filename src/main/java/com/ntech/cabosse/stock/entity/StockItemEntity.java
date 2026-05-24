package com.ntech.cabosse.stock.entity;

import com.ntech.cabosse.article.entity.ArticleType;
import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Vue agrégée du stock d'un article sur un site précis. Tenant-scopée
 * (collection {@code stock_items}). Identité métier : couple
 * {@code (articleId, siteId)} — index unique en BD.
 *
 * <p>Cette entité existe pour offrir une lecture O(1) de la position
 * stock (qté courante + CMUP courant), sans rejouer tout le journal des
 * mouvements à chaque consultation. Le journal exhaustif vit dans
 * {@link StockMovementEntity}.</p>
 *
 * <p>Le CMUP est <strong>par couple (article, site)</strong> :
 * conformément à la règle 5.3 du CDC (valorisation permanente CMUP), un
 * même article peut avoir un CMUP différent à Méagui et à Abidjan. Le
 * recalcul se fait via un pipeline d'agrégation MongoDB atomique côté
 * service — pas de transaction multi-doc requise.</p>
 *
 * <p>Snapshots {@code articleCode}/{@code articleName}/{@code articleUnit}
 * sont posés pour permettre des listes/exports rapides sans join. Ils
 * sont rafraîchis à chaque mouvement (cf. {@code StockService}).</p>
 */
public class StockItemEntity {

    @BsonId
    public UUID id;

    public UUID articleId;
    public UUID siteId;

    // ─── Snapshots article (rafraîchis à chaque mvt) ───
    public String articleCode;
    public String articleName;
    public String articleUnit;
    /**
     * Type de l'article snapshoté pour permettre le filtrage par
     * catégorie sans join. Sérialisé en BD comme le {@code name()} de
     * l'enum (convention PojoCodec Mongo).
     */
    public ArticleType articleType;

    /**
     * Quantité courante. Peut être négative si {@code allowNegativeStock}
     * est activé au niveau tenant (utile pour ajustements physiques en
     * retard de saisie).
     */
    public BigDecimal quantity = BigDecimal.ZERO;

    /**
     * Coût Moyen Unitaire Pondéré courant en FCFA. Recalculé à chaque
     * entrée selon la formule :
     * {@code CMUP_after = (qty_before * CMUP_before + qty_in * pu_in) / qty_after}.
     * Inchangé sur les sorties et ajustements. Vaut 0 si quantity == 0.
     */
    public BigDecimal cmupFcfa = BigDecimal.ZERO;

    /**
     * Seuil d'alerte spécifique au site. Hérité d'
     * {@code Article.alertThreshold} à la première entrée, modifiable
     * ensuite par site (un même article peut avoir un seuil différent à
     * Méagui et à Abidjan suivant la consommation locale).
     */
    public BigDecimal alertThreshold;

    /** Horodatage du dernier mouvement appliqué — pour tri "récents". */
    public Instant lastMovementAt;

    public Instant createdAt;
    public Instant updatedAt;

    /** Lock optimiste — incrémenté à chaque write. */
    public long version = 0L;

    public StockItemEntity() {}
}
