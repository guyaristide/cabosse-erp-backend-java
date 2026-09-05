package com.ntech.cabosse.production.entity;

import com.ntech.cabosse.recipe.entity.RecipeStep;
import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ordre de fabrication (OF). Tenant-scopé (collection
 * {@code manufacturing_orders}).
 *
 * <p>Cycle de vie : {@link OfStatus#DRAFT} → {@link OfStatus#IN_PROGRESS}
 * (au démarrage : matières consommées en OUT) → étapes successives si
 * recette avec étapes → {@link OfStatus#COMPLETED} (PF entré en stock
 * avec CMUP recalculé depuis le coût matière). {@link OfStatus#CANCELLED}
 * possible à toute étape (compensations adaptées au statut antérieur).</p>
 *
 * <p>Les snapshots (recette, étapes, articles) sont figés à la création
 * de l'OF — il reste lisible et auditable même si les référentiels
 * évoluent ensuite.</p>
 */
public class ManufacturingOrderEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code OF-YYYY-NNNN}. Unique par tenant. */
    public String ref;

    public UUID siteId;
    public String siteName;

    public OfStatus status = OfStatus.DRAFT;

    // ─── Recette source (snapshots figés à la création) ───
    public UUID recipeId;
    public String recipeCode;
    public String recipeName;
    public BigDecimal recipeYieldQty;
    public String recipeYieldUnit;
    /** Liste figée des étapes de la recette au moment de la création. */
    public List<RecipeStep> recipeStepsSnapshot = new ArrayList<>();

    // ─── Produit fini cible ───
    public UUID finishedProductId;
    public String finishedProductCode;
    public String finishedProductName;
    public String finishedProductUnit;
    /**
     * Poids unitaire du PF en grammes — snapshoté à la création depuis
     * {@code ArticleEntity.unitWeightGrams}. Sert au calcul du poids total
     * produit et du rendement. {@code null} si le PF n'a pas de poids
     * unitaire renseigné au moment de la création.
     */
    public Integer finishedProductUnitWeightGrams;

    // ─── Planification ───
    /** Quantité de PF demandée. */
    public BigDecimal plannedQty;
    /** Quantité de PF réellement produite — saisie à la complétion. */
    public BigDecimal producedQty;
    /** Étiquette de lot. Par défaut {@code LOT-YYYY-NNNN}, modifiable. */
    public String lotRef;
    public LocalDate scheduledDate;
    public Instant startedAt;
    public Instant completedAt;

    // ─── Suivi par étapes ───
    /**
     * Index de l'étape courante dans {@code recipeStepsSnapshot}. {@code null}
     * tant que l'OF est en DRAFT ou si la recette n'a pas d'étapes.
     */
    public Integer currentStepIndex;
    public List<StepProgress> stepHistory = new ArrayList<>();

    // ─── Consommation matière (snapshotée au démarrage) ───
    public List<ConsumptionLine> consumptionLines = new ArrayList<>();
    /** Somme des {@code totalCost} des lignes — figée au démarrage. */
    public BigDecimal totalMaterialCost;
    /** CMUP appliqué au mouvement IN du PF à la complétion. */
    public BigDecimal cmupAtCompletion;

    // ─── KPIs production (saisis à la complétion) ───
    /**
     * Durée effective de la production en heures. Saisie au moment de
     * {@code complete()}. {@code null} pour les OF non terminés.
     */
    public Integer actualDurationHours;
    /**
     * Nombre d'opérateurs mobilisés sur l'OF. Saisi à {@code complete()}.
     * {@code null} pour les OF non terminés.
     */
    public Integer operatorsCount;

    public String notes;
    public ManufacturingOrderCancellation cancellation;

    /**
     * Campagne de rattachement, déduite de {@link #scheduledDate}. Nulle quand aucune
     * campagne ne couvre la date et qu'aucune n'est ouverte.
     */
    public UUID campaignId;

    /** Année de la campagne, dénormalisée pour les regroupements. */
    public Integer campaignYear;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    /** Lock optimiste — incrémenté à chaque écriture (cf. ManufacturingOrderRepository.replace). */
    public long version = 0L;

    public ManufacturingOrderEntity() {}
}
