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

    public String truckNumber;

    /** Fournisseur du carnet : délégué ou producteur, code et nom libres. */
    public String supplierCode;
    public String supplierName;

    /** Délégué collecteur reconnu dans le référentiel, s'il l'a été. */
    public UUID delegateSupplierId;

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

    public Instant createdAt;
    public String createdByEmail;
    public Instant updatedAt;
}
