package com.ntech.cabosse.shared.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * Pièce justificative rattachée à une opération.
 *
 * <p>Le binaire vit dans {@code cloud_files} ; l'entité métier ne porte que
 * ce qu'il faut pour lister les pièces sans ouvrir aucun fichier : son nom,
 * son poids, qui l'a déposée et quand. Une demande signée, un procès-verbal
 * ou une pièce d'identité se reconnaissent à leur libellé, pas à un
 * identifiant.</p>
 */
public class AttachmentRef {

    /** Identifiant du fichier dans {@code cloud_files}. */
    public UUID fileId;

    public String fileName;
    public String mimeType;
    public long sizeBytes;

    /** Libellé donné au dépôt : « Demande signée », « PV du conseil »… */
    public String label;

    public Instant uploadedAt;
    public String uploadedByEmail;

    public AttachmentRef() {}
}
