package com.ntech.cabosse.agriculture.qc.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Contrôle qualité d'un lot de fèves post-séchage. Tenant-scopé
 * (collection {@code bean_quality_checks}).
 *
 * <p>Disponible si la capacité
 * {@link com.ntech.cabosse.tenant.capability.TenantCapability#HAS_DRYING}
 * est active (le QC fèves est consécutif au séchage).</p>
 *
 * <p>À la validation ({@code conformOverall=true} + service.validate()),
 * un mouvement stock IN est généré automatiquement sur l'article fève
 * cible avec un {@code lotRef} {@code LOT-FEVE-YYYY-NNNN}, alimentant
 * le magasin. Le grade est snapshoté pour préserver la traçabilité
 * comptable et commerciale.</p>
 */
public class BeanQualityCheckEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code QC-YYYY-NNNN}. Unique par tenant. */
    public String ref;

    /** FK vers {@code DryingBatchEntity}. */
    public UUID dryingBatchId;
    public String dryingBatchRef;

    /** Nombre de fèves coupées pour le cut test. */
    public Integer cutTestSampleCount;

    /** % de fèves bien fermentées (cible cacao : ≥ 60 % pour GR1). */
    public BigDecimal wellFermentedPct;

    /** Taux d'humidité mesuré au QC (%). Cible ≤ 7,5 pour le cacao. */
    public BigDecimal humidityPct;

    /** % de fèves défectueuses (moisies, plates, germées, etc.). */
    public BigDecimal defectsPct;

    public BeanGrade grade;

    /**
     * Décision globale du contrôle :
     * - {@code true} → conforme, peut entrer en stock (mouvement IN auto)
     * - {@code false} → non conforme, lot écarté (notes obligatoires)
     */
    public boolean conformOverall;

    /** Quantité fèves validées (kg) — base du mouvement stock IN. */
    public BigDecimal acceptedKg;

    /** FK vers l'{@code ArticleEntity} matière fève destinataire. */
    public UUID beanArticleId;
    public String beanArticleCode;
    public String beanArticleName;

    /** FK vers le {@code SiteEntity} de stockage. */
    public UUID siteId;
    public String siteName;

    /**
     * Référence de lot générée lors de la validation : {@code LOT-FEVE-YYYY-NNNN}.
     * Sert de pivot pour la traçabilité aval (toute matière sortie du stock
     * peut remonter à ce lot via {@code stock_movements.lotRef}).
     */
    public String lotRef;

    public Instant validatedAt;
    public String validatedByEmail;
    public boolean stockMovementCreated;

    public String notes;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    public BeanQualityCheckEntity() {}
}
