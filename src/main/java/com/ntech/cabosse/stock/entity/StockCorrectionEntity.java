package com.ntech.cabosse.stock.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Une correction de stock du magasin : de la matière sort sans acheteur.
 *
 * <p>Le magasinier brasse un lot qui lui paraît douteux pour en retirer
 * les impuretés. Il en sort des sacs et un poids, et la fiche du jour
 * doit le montrer : son carnet porte une ligne « Perte de poids pour
 * brassage », sacs et poids en négatif, sans prix ni montant, et le stock
 * de clôture en tient compte.</p>
 *
 * <p>Un mouvement de stock seul ne suffisait pas : le stock ne compte pas
 * les sacs, et c'est pourtant en sacs que le magasin se tient. La
 * correction porte donc les deux, et le mouvement qu'elle engendre reste
 * la seule vérité sur la matière.</p>
 *
 * <p>Elle ne se modifie pas : c'est un constat daté. Une erreur se
 * rattrape par un inventaire physique, qui est fait pour ça.</p>
 */
public class StockCorrectionEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code COR-2026-0001}. Unique par tenant. */
    public String ref;

    /** Jour du brassage, pas celui de la saisie. */
    public LocalDate date;

    public UUID siteId;
    public String siteName;
    public UUID articleId;
    public String articleCode;
    public String articleName;
    public String articleUnit;

    /** Ce que le magasinier écrit dans la colonne du nom de son carnet. */
    public String reason;

    /** Sacs retirés, toujours positif. Nul quand le brassage n'en retire pas. */
    public Integer bags;

    /** Poids retiré, toujours positif. */
    public BigDecimal weightKg;

    /** Coût moyen de l'article à la date d'effet, figé au constat. */
    public BigDecimal unitPrice;

    /** {@code weightKg * unitPrice} : ce que la perte coûte. */
    public BigDecimal value;

    public String notes;

    /** Le mouvement de sortie engendré. La matière n'a pas d'autre vérité. */
    public UUID movementId;

    /** Pièce comptable de régularisation, quand la perte a une valeur. */
    public String pieceRef;

    public UUID campaignId;
    public Integer campaignYear;

    public String createdBy;
    public Instant createdAt;
}
