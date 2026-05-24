package com.ntech.cabosse.stock.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Une ligne du journal des mouvements de stock. Tenant-scopée (collection
 * {@code stock_movements}).
 *
 * <p>Chaque mouvement est <strong>immuable</strong> : on n'édite jamais
 * un mvt enregistré. Les corrections passent par un nouveau mouvement
 * compensatoire (ex. contre-passation d'une RD → OUT négatifs miroir des
 * IN d'origine).</p>
 *
 * <p>Les champs {@code quantityAfter} et {@code cmupAfterFcfa} sont
 * <em>figés</em> au moment où le mouvement a été appliqué — ils donnent
 * l'état du {@link StockItemEntity} immédiatement après le mouvement.
 * Ne pas confondre avec l'état courant du stock, qui évolue au fil des
 * mouvements ultérieurs.</p>
 *
 * <p>{@link #sourceEntityId} permet de remonter à l'entité métier qui a
 * généré le mouvement (RD, BC, OF, vente). Ce lien est inverse au lien
 * SourceEntity → Movement (pas de FK explicite dans Mongo, on retrouve
 * par requête index).</p>
 */
public class StockMovementEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code MV-2026-000001}. Unique par tenant. */
    public String ref;

    // ─── Cible du mouvement ───
    public UUID articleId;
    public UUID siteId;
    public String articleCode;
    public String articleName;
    public String articleUnit;
    public String siteName;

    public MovementKind kind;

    /**
     * Quantité signée : positive pour {@link MovementKind#IN},
     * {@link MovementKind#OPENING}, {@link MovementKind#TRANSFER_IN} ;
     * négative pour {@link MovementKind#OUT}, {@link MovementKind#TRANSFER_OUT} ;
     * de signe variable pour {@link MovementKind#ADJUSTMENT}.
     */
    public BigDecimal quantitySigned;

    /**
     * Prix unitaire en FCFA :
     * <ul>
     *   <li>Pour une entrée : prix d'achat / d'amorçage / CMUP du site
     *       source pour un transfert.</li>
     *   <li>Pour une sortie : CMUP courant (snapshot au moment du mvt)
     *       — important pour la traçabilité comptable.</li>
     *   <li>Pour un ajustement : null (le mouvement n'a pas de valeur
     *       unitaire propre).</li>
     * </ul>
     */
    public BigDecimal unitPriceFcfa;

    /** {@code |quantitySigned| * unitPriceFcfa}. Null si unitPriceFcfa null. */
    public BigDecimal totalFcfa;

    /** Quantité du {@link StockItemEntity} APRÈS application du mouvement. */
    public BigDecimal quantityAfter;

    /** CMUP du {@link StockItemEntity} APRÈS application du mouvement. */
    public BigDecimal cmupAfterFcfa;

    // ─── Origine métier ───
    public MovementSource sourceType;
    /** Référence humaine de l'origine ({@code RD-2026-0007}, {@code BC-2026-0042}…). */
    public String sourceRef;
    /** UUID de l'entité métier d'origine ({@code DirectReceiptEntity.id}, etc.). */
    public UUID sourceEntityId;

    /**
     * Identifiant partagé entre les deux mouvements d'une paire de
     * transfert ({@link MovementKind#TRANSFER_OUT} / {@link MovementKind#TRANSFER_IN}).
     * Null pour les autres types.
     */
    public UUID transferId;

    /** Motif obligatoire pour {@link MovementKind#ADJUSTMENT}, optionnel ailleurs. */
    public String reason;

    /**
     * Étiquette de lot. Renseignée typiquement au moment d'une production
     * (mouvement IN du PF). Permet la recherche "tous les mouvements de
     * ce lot" via un index sparse côté BD.
     */
    public String lotRef;

    public String notes;

    /** Email du user qui a posé le mouvement. */
    public String actorEmail;

    /**
     * Date d'effet du mouvement (peut différer de {@link #createdAt} en
     * cas de saisie rétroactive — typiquement amorçage initial ou
     * inventaire avec date antérieure). Sert au calcul de snapshot à
     * date.
     */
    public Instant occurredAt;

    public Instant createdAt;

    public StockMovementEntity() {}
}
