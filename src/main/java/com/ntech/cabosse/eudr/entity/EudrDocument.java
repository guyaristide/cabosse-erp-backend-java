package com.ntech.cabosse.eudr.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Pièce justificative attachée à un dossier EUDR. Embed dans
 * {@link EudrDossierEntity#documents}.
 */
public class EudrDocument {

    /** Identifiant stable du document dans le dossier. */
    public UUID id;

    public EudrDocumentType type;

    /** Libellé court ("Titre foncier 2018", "Analyse GFW Q1 2026"). */
    public String label;

    /** Référence vers {@code CloudFileEntity.id}. */
    public UUID fileId;

    /** Date d'émission du document (sur la pièce elle-même). */
    public java.time.LocalDate issuedOn;

    /** Date d'expiration prévue (pour les pièces datées). */
    public java.time.LocalDate expiresOn;

    public Instant uploadedAt;
    public String uploadedByEmail;
    public String notes;

    public EudrDocument() {}
}
