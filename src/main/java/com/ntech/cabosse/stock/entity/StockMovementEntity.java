package com.ntech.cabosse.stock.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Une ligne du journal des mouvements de stock. Tenant-scopée (collection
 * {@code stock_movements}).
 *
 * <p>Les champs métier d'un mouvement sont <strong>immuables</strong> :
 * on n'édite jamais un mvt enregistré. Les corrections passent par un
 * nouveau mouvement compensatoire (ex. contre-passation d'une RD → OUT
 * négatifs miroir des IN d'origine).</p>
 *
 * <p>Les champs {@code quantityAfter} et {@code cmupAfterFcfa} donnent
 * l'état du {@link StockItemEntity} immédiatement après le mouvement
 * <em>dans l'ordre chronologique</em> ({@code occurredAt}). Une saisie
 * rétroactive déclenche le rejeu du couple (article, site) et la
 * réécriture de ces instantanés — ainsi que du PU des sorties, défini
 * comme photo du CMUP — sur tous les mouvements du couple. Ce sont les
 * seuls champs réécrits après insertion.</p>
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

    /**
     * Sur une entrée : {@code true} si le CMUP a pris le PU de l'entrée
     * au lieu de se pondérer (livraison délégué en mode « par lot »).
     * Persisté pour que le rejeu chronologique d'une saisie rétroactive
     * reproduise la valorisation d'origine. {@code null} = pondération
     * standard (et valeur des mouvements antérieurs à ce champ).
     */
    public Boolean replacesCmup;

    /**
     * Mouvement neutralisé par une contre-passation, avec son miroir.
     *
     * <p>Une entrée annulée et sa sortie compensatoire forment une paire :
     * elles restent au journal, pour la piste d'audit, mais sortent de la
     * valorisation. Une simple sortie compensatoire retirait bien la
     * quantité, mais laissait le coût moyen pollué par le prix de
     * l'opération annulée — et en mode « par lot », où l'entrée écrase le
     * CMUP au lieu de le pondérer, ce prix restait purement et simplement
     * en place. En excluant la paire, le rejeu chronologique retrouve
     * exactement l'état d'avant l'opération.</p>
     */
    public Boolean excludedFromValuation;

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


    /**
     * Campagne de rattachement, déduite de {@link #occurredAt}. Nulle quand aucune
     * campagne ne couvre la date et qu'aucune n'est ouverte.
     */
    public UUID campaignId;

    /** Année de la campagne, dénormalisée pour les regroupements. */
    public Integer campaignYear;
    public Instant createdAt;

    public StockMovementEntity() {}
}
