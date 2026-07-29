package com.ntech.cabosse.members.entity;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Pièce portée par un producteur : pièce d'identité, ou carte délivrée par
 * un tiers de la filière. Sub-document de {@link MemberEntity}, embarqué
 * dans la liste {@code identityDocuments}.
 *
 * <p>Liste plutôt que couple unique : un producteur cumule fréquemment une
 * carte d'identité, un identifiant national et, selon la filière, une carte
 * délivrée par l'organisme régulateur. Toutes ont la même forme, un type,
 * un numéro, un émetteur, une validité et une photocopie, et se rangent
 * donc au même endroit.</p>
 *
 * <p>Ce que la pièce <em>permet</em> ne se décide pas ici mais sur son type
 * (référentiel {@code id_document_types}) : établir l'identité, servir de
 * clé de rapprochement dans un fichier importé, ou les deux.</p>
 *
 * <p>Le scan éventuel vit dans {@code cloud_files} ; seule sa référence est
 * conservée ici (cf. file-storage.md).</p>
 */
public class MemberIdentityDocument {

    /** Libellé du type (référentiel {@code id_document_types}). */
    public String type;

    /** Numéro porté par la pièce, tel qu'il y figure. */
    public String number;

    /**
     * Numéro réduit à sa forme comparable (majuscules, sans espaces ni
     * ponctuation). Sert au rapprochement et au contrôle d'unicité ; jamais
     * affiché. Renseigné par le service, jamais par le client.
     */
    public String normalizedNumber;

    /** Autorité qui a délivré la pièce. Facultatif. */
    public String issuedBy;

    /** Fin de validité. Facultative, non bloquante : information de dossier. */
    public LocalDate expiresAt;

    /** Référence vers {@code CloudFileEntity.id} pour le scan. Facultative. */
    public UUID fileId;

    public MemberIdentityDocument() {}

    public MemberIdentityDocument(String type, String number, UUID fileId) {
        this.type = type;
        this.number = number;
        this.fileId = fileId;
    }

    /**
     * Forme comparable d'un numéro : deux saisies qui ne diffèrent que par
     * la casse, les espaces ou les tirets désignent la même carte, et
     * doivent donc entrer en collision.
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String cleaned = raw.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return cleaned.isEmpty() ? null : cleaned;
    }
}
