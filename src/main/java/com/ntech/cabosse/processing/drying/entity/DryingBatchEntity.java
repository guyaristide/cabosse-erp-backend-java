package com.ntech.cabosse.processing.drying.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Batch de séchage des fèves. Tenant-scopé (collection {@code drying_batches}).
 *
 * <p>Disponible si la capacité
 * {@link com.ntech.cabosse.tenant.capability.TenantCapability#HAS_DRYING}
 * est active. Reçoit les fèves d'un ou plusieurs bacs de fermentation
 * et les conduit jusqu'à l'humidité cible (≤ 7,5 % pour le cacao).</p>
 *
 * <p>Une fois COMPLETED, le batch alimente un contrôle qualité fèves
 * ({@code BeanQualityCheckEntity}) qui décidera de l'entrée stock.</p>
 */
public class DryingBatchEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code SEC-YYYY-NNNN}. */
    public String ref;

    /** UUIDs des bacs de fermentation alimentant ce séchage. */
    public List<UUID> fermentationBatchIds = new ArrayList<>();

    /** Snapshots refs bacs pour affichage rapide. */
    public List<String> fermentationBatchRefs = new ArrayList<>();

    public DryingMethod method;

    public DryingBatchStatus status = DryingBatchStatus.DRYING;

    public Instant startedAt;
    public Instant completedAt;

    /** Durée totale en heures (cumulée si interrompue). */
    public Integer durationHours;

    /** Poids entrée séchage (fèves humides post-fermentation, kg). */
    public BigDecimal weightInKg;

    /** Poids sortie séchage (kg). */
    public BigDecimal weightOutKg;

    /** Taux d'humidité final mesuré (%). Cible cacao : ≤ 7,5. */
    public BigDecimal finalHumidityPct;

    /** Perte de masse en % entre entrée et sortie (dérivée). */
    public BigDecimal weightLossPct;

    public String notes;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    public DryingBatchEntity() {}
}
