package com.ntech.cabosse.iddocument.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Type de pièce porté par un producteur (référentiel tenant). Alimente la
 * liste déroulante de la fiche membre : pièces d'identité (carte nationale,
 * passeport, attestation) mais aussi cartes délivrées par un tiers de la
 * filière, qui ont la même forme (un émetteur, un numéro, une validité, une
 * photocopie).
 *
 * <p>Deux usages distincts, portés par le type et non par la pièce :</p>
 * <ul>
 *   <li>{@link #identityProof} : la pièce établit qui est la personne. Elle
 *       compte pour la complétude du dossier et la vigilance sur les
 *       paiements.</li>
 *   <li>{@link #usableAsProducerRef} : le numéro identifie le producteur
 *       dans un fichier importé. C'est le cas d'une carte filière, jamais
 *       d'un passeport.</li>
 * </ul>
 *
 * <p>Les deux sont indépendants : une carte filière retrouve un producteur
 * sans prouver son identité, une carte nationale prouve l'identité sans
 * servir de clé dans les fichiers.</p>
 *
 * <p>Tenant-scoped, éditable, sans seed : la liste se construit à l'usage.
 * Une structure dont la filière ne délivre aucune carte n'aura jamais de
 * type coché {@code usableAsProducerRef}, et le rapprochement se fera sur
 * le seul code interne du membre.</p>
 */
public class IdDocumentTypeEntity {

    @BsonId
    public UUID id;

    /** Code stable (slug) ; FK technique éventuelle. */
    public String code;

    /** Nom affiché et stocké (ex. {@code "CNI"}, {@code "Passeport"}). */
    public String name;

    /**
     * La pièce établit l'identité de la personne. Vrai par défaut : c'est
     * ce que sont la plupart des types saisis.
     */
    public boolean identityProof = true;

    /**
     * Le numéro de la pièce identifie le producteur dans un fichier
     * importé. Faux par défaut, y compris pour un type créé
     * automatiquement à l'import : rien ne devient une clé par accident.
     */
    public boolean usableAsProducerRef = false;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public IdDocumentTypeEntity() {}
}
