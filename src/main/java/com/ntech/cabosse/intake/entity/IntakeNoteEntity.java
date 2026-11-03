package com.ntech.cabosse.intake.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bordereau de réception du magasin (épic CE-218, DEC-41) : le constat
 * physique d'une livraison, camion par camion, des mains d'un délégué ou
 * d'un producteur.
 *
 * <p>Il n'écrit <strong>pas</strong> le stock : la matière n'entre que
 * par les reçus d'achat producteur, seuls porteurs de l'origine de
 * chaque kilo. Le bordereau attend sa comptabilisation (import du détail
 * par producteur extrait du SNT), qui crée les reçus et fige l'écart
 * entre la pesée du camion et la somme comptabilisée.</p>
 */
public class IntakeNoteEntity {

    public static final String STATUS_TO_ACCOUNT = "TO_ACCOUNT";
    public static final String STATUS_ACCOUNTED = "ACCOUNTED";

    @BsonId
    public UUID id;

    /** N° du bordereau tel que le carnet le porte (BR0254). Unique. */
    public String ref;

    public LocalDate date;

    /** Mouvement tel que le fichier l'écrit (« Entrée Stock »). */
    public String movement;

    public String campaignLabel;
    public UUID campaignId;

    /** Produit tel que le carnet le nomme : une valeur, pas une filière. */
    public String productLabel;

    /**
     * L'article du référentiel que le camion apporte.
     *
     * <p>Le produit n'était qu'un texte libre, et l'article n'était choisi
     * qu'à la comptabilisation, sans lien avec ce qui avait été tapé :
     * « comment le système pourra faire l'association avec tout ce qui
     * est stockable ? » (03/11/2026). Posé à la saisie, reconnu par son
     * nom à l'import, il est repris par la comptabilisation et la
     * répartition sans être redemandé. Nul sur les bordereaux anciens
     * dont le libellé ne correspond à aucun article.</p>
     */
    public UUID articleId;

    public String truckNumber;

    /** Fournisseur du carnet : délégué ou producteur, code et nom libres. */
    public String supplierCode;
    public String supplierName;

    /** Délégué collecteur reconnu dans le référentiel, s'il l'a été. */
    public UUID delegateSupplierId;

    /**
     * Fournisseur du bordereau, délégué ou non.
     *
     * <p>Seuls les délégués étaient reconnus : un fournisseur ordinaire
     * restait un nom sans fiche, et la comptabilisation n'avait alors
     * personne sur qui poser le reçu. La coopérative achète pourtant
     * aussi bien à un délégué qu'à un fournisseur simple, sociétaire ou
     * non (12/10/2026).</p>
     */
    public UUID supplierId;

    /** N° d'ordre de la ligne sur le bordereau papier. */
    public Integer lineNumber;

    public BigDecimal grossWeightKg;
    public Integer bagCount;
    public BigDecimal netWeightKg;

    /** Site du magasin qui a constaté la réception. */
    public UUID siteId;

    public String status = STATUS_TO_ACCOUNT;

    // ─── Comptabilisation ───
    public Instant accountedAt;
    public String accountedByEmail;
    /** Somme des poids des reçus créés à la comptabilisation. */
    public BigDecimal accountedWeightKg;
    public BigDecimal accountedAmount;
    public List<String> receiptRefs;
    /**
     * Les lignes du fichier qu'aucun reçu ne porte, avec leur raison.
     *
     * <p>Un bordereau comptabilisé dont cette liste n'est pas vide est
     * incomplet : il lui manque de la matière, et le dire est le seul
     * moyen que quelqu'un aille chercher pourquoi.</p>
     */
    public List<IntakeSkippedRow> skippedRows;


    /**
     * Pièces justificatives : le bordereau papier, la pesée, le ticket de bascule. Déposées à la saisie ou plus tard,
     * quand le papier remonte (demandé le 22/10/2026).
     */
    public java.util.List<com.ntech.cabosse.shared.storage.AttachmentRef> attachments =
            new java.util.ArrayList<>();

    public Instant createdAt;
    public String createdByEmail;
    public Instant updatedAt;
}
