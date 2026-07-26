package com.ntech.cabosse.members.entity;

import java.util.UUID;

/**
 * Pièce d'identité d'un producteur (backlog MEM-07). Sub-document de
 * {@link MemberEntity}, embarqué dans la liste {@code identityDocuments}.
 *
 * <p>Liste plutôt que couple unique : un producteur cumule fréquemment une
 * carte d'identité et un identifiant national, parfois une carte consulaire
 * ou une attestation. Le {@code type} porte le libellé choisi dans le
 * référentiel tenant des types de pièces, le {@code number} la valeur.</p>
 *
 * <p>Le scan éventuel vit dans {@code cloud_files} ; seule sa référence est
 * conservée ici (cf. file-storage.md).</p>
 */
public class MemberIdentityDocument {

    /** Libellé du type de pièce (référentiel {@code id_document_types}). */
    public String type;

    /** Numéro porté par la pièce. */
    public String number;

    /** Référence vers {@code CloudFileEntity.id} pour le scan. Facultative. */
    public UUID fileId;

    public MemberIdentityDocument() {}

    public MemberIdentityDocument(String type, String number, UUID fileId) {
        this.type = type;
        this.number = number;
        this.fileId = fileId;
    }
}
