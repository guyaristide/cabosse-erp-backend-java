package com.ntech.cabosse.outflow.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Bordereau de sortie du carnet du magasin (épic CE-218) : le constat
 * physique d'un chargement, bordereau de réception par bordereau de
 * réception, vers un client.
 *
 * <p>Il n'écrit <strong>pas</strong> le stock : la sortie comptable est
 * portée par la vente (import des ventes, N° BS et N° chargement), qui
 * sort le stock au CMUP. Ce constat s'y rapproche par ces deux numéros ;
 * l'écran montre ce qui a trouvé sa vente et ce qui l'attend.</p>
 */
public class OutflowNoteEntity {

    @BsonId
    public UUID id;

    /** N° du bordereau tel que le carnet le porte (BR0254). Unique. */
    public String ref;

    public LocalDate date;

    /** Mouvement tel que le fichier l'écrit (« Sortie Stock »). */
    public String movement;

    public String campaignLabel;
    public UUID campaignId;

    /** Produit tel que le carnet le nomme : une valeur, pas une filière. */
    public String productLabel;

    /** N° du bordereau de sortie du carnet, commun aux lignes du chargement. */
    public String dispatchNoteNumber;

    /** N° du chargement, avec le N° BS la clé de rapprochement à la vente. */
    public String loadingNumber;

    public String truckNumber;

    public String destination;

    /** Client du carnet : code et nom libres. */
    public String customerCode;
    public String customerName;

    /** Client reconnu dans le référentiel, s'il l'a été. */
    public UUID customerId;

    /** N° d'ordre de la ligne sur le bordereau papier. */
    public Integer lineNumber;

    public BigDecimal grossWeightKg;
    public Integer bagCount;
    public BigDecimal netWeightKg;

    /** Site du magasin qui a constaté la sortie. */
    public UUID siteId;

    public Instant createdAt;
    public String createdByEmail;
    public Instant updatedAt;
}
