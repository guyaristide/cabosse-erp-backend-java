package com.ntech.cabosse.crop.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Culture pratiquée sur une parcelle (backlog PARC-02). Référentiel tenant :
 * cacao, café, hévéa, anacarde, vivrier, etc.
 *
 * <p>Tenant-scoped et éditable, sans seed : la structure construit sa liste à
 * l'usage, ce qui garde la plateforme agnostique de la filière. Consommé par
 * la fiche parcelle (culture principale et cultures secondaires) et par la
 * fiche signalétique du producteur.</p>
 */
public class CropEntity {

    @BsonId
    public UUID id;

    /** Code stable (slug), référencé par {@code ParcelEntity.cropCode}. */
    public String code;

    /** Nom affiché (ex. {@code "Cacao"}, {@code "Café"}, {@code "Hévéa"}). */
    public String name;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public CropEntity() {}
}
