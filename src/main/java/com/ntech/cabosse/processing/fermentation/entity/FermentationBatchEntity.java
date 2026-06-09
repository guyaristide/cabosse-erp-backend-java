package com.ntech.cabosse.processing.fermentation.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bac de fermentation. Tenant-scopé (collection {@code fermentation_batches}).
 *
 * <p>Disponible si la capacité
 * {@link com.ntech.cabosse.tenant.capability.TenantCapability#HAS_FERMENTATION}
 * est active (cacao, café spécialty, vanille).</p>
 *
 * <p>Un bac agrège 1..N récoltes ({@link #harvestIds}), reçoit les
 * fèves fraîches, puis on enregistre des mesures de température et
 * des brassages tout au long du cycle (5-7 jours pour le cacao).</p>
 *
 * <p>Au passage en {@link FermentationBatchStatus#COMPLETED}, le bac est
 * vidé et alimente un batch de séchage en aval.</p>
 */
public class FermentationBatchEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code BAC-YYYY-NNNN}. */
    public String ref;

    /** UUIDs des récoltes chargées dans le bac. */
    public List<UUID> harvestIds = new ArrayList<>();

    /** Snapshots codes récoltes pour affichage rapide. */
    public List<String> harvestCodes = new ArrayList<>();

    public FermentationBatchStatus status = FermentationBatchStatus.PREPARING;

    public Instant startedAt;
    public Instant completedAt;

    /** Poids des fèves chargées dans le bac (kg). */
    public BigDecimal weightInKg;

    /** Poids des fèves sorties du bac après fermentation (kg) — généralement inférieur. */
    public BigDecimal weightOutKg;

    public List<TemperatureReading> temperatureReadings = new ArrayList<>();
    public List<Turning> turnings = new ArrayList<>();

    /**
     * Estimation qualitative de la fermentation à la sortie ({@code GR1},
     * {@code GR2}, {@code HG}, etc.). Estimation chef d'atelier, à
     * confirmer par le contrôle qualité QC post-séchage.
     */
    public String finalGradeEstimate;

    public String notes;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    public FermentationBatchEntity() {}
}
