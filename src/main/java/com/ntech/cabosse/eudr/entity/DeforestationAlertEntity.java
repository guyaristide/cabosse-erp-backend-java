package com.ntech.cabosse.eudr.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Alerte de déforestation détectée (ou saisie) sur une parcelle.
 * Tenant-scopée (collection {@code deforestation_alerts}).
 *
 * <p>Sources possibles ({@link #sourceProvider}) :</p>
 * <ul>
 *   <li>{@code GFW} — Global Forest Watch (alertes RADD, GLAD-L, GLAD-S2).</li>
 *   <li>{@code COPERNICUS} — Sentinel-2 via Copernicus Browser ou un
 *       prestataire SIG (ex. Vivid Economics, IIASA).</li>
 *   <li>{@code MANUAL} — saisie par l'agronome ou l'auditeur après visite
 *       terrain.</li>
 * </ul>
 *
 * <p>Au MVP, le service {@code DeforestationCheckService} est un
 * placeholder en attendant l'intégration API GFW. Les alertes peuvent
 * être saisies manuellement via l'UI EUDR.</p>
 */
public class DeforestationAlertEntity {

    @BsonId
    public UUID id;

    public UUID parcelId;
    public String parcelCode;
    public String parcelName;

    /** Date de détection (satellite) ou de saisie (manual). */
    public LocalDate detectedAt;

    public DeforestationSeverity severity;

    /** Surface impactée estimée (hectares). */
    public BigDecimal areaHaImpacted;

    /** {@code GFW}, {@code COPERNICUS}, {@code MANUAL}, ou nom prestataire. */
    public String sourceProvider;

    /** Référence externe (ex. ID alerte RADD, numéro rapport SIG). */
    public String sourceReference;

    public DeforestationAlertStatus status = DeforestationAlertStatus.NEW;

    /** Action corrective appliquée (texte libre, renseigné au passage RESOLVED). */
    public String remediationAction;
    public Instant resolvedAt;
    public String resolvedByEmail;

    public String notes;

    public Instant createdAt;
    public UUID createdBy;
    public String createdByEmail;

    public DeforestationAlertEntity() {}
}
