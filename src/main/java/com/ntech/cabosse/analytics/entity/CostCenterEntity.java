package com.ntech.cabosse.analytics.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Centre de coût de la comptabilité analytique (backlog CPT-09).
 * Tenant-scoped : chaque tenant définit ses propres centres. Les valeurs
 * seedées (ADM, COL, CERT, AGRO, GEN, PE, DIG) sont illustratives et
 * pleinement éditables — la coopérative ajuste codes, libellés et
 * périmètres à sa structure réelle.
 *
 * <p>L'imputation analytique ne concerne que les comptes de charges
 * (classe 6 SYSCOHADA) : le centre de coût se pose sur les lignes de
 * pièces dont le compte relève de cette classe.</p>
 */
public class CostCenterEntity {

    @BsonId
    public UUID id;

    /** Code court stable, porté par les lignes de pièces (ex. {@code COL}). */
    public String code;

    public String name;
    public String description;

    public boolean active = true;

    /**
     * Programme budgétaire imputé par défaut aux charges de ce centre
     * (backlog CPT-10, règle v8). {@code null} = aucune imputation
     * programme. Code du référentiel {@code programs}.
     */
    public String defaultProgram;

    /** Projet du programme imputé par défaut (optionnel). */
    public String defaultProject;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
}
