package com.ntech.cabosse.commodity.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Un brouillon de vente en gros (VTE-28, demandé le 12/11/2026).
 *
 * <p>Ce n'est pas une vente : rien n'est sorti du stock, rien n'est
 * écrit au journal, aucun numéro n'est pris. C'est la saisie telle que la
 * personne l'a laissée, conservée au serveur pour qu'elle la retrouve
 * d'un autre poste ou d'une autre journée, puis la valide calmement.
 * Une saisie incomplète s'y garde : c'est précisément ce qu'on attend
 * d'un brouillon.</p>
 *
 * <p>La saisie est conservée telle quelle, en JSON ({@link #payloadJson}) :
 * un brouillon n'est pas un montant comptable, et le relire dans la forme
 * exacte où il a été écrit vaut mieux qu'une seconde copie du modèle de
 * vente à tenir à jour. Quelques champs sont recopiés à côté pour la
 * liste, sans rouvrir le JSON.</p>
 */
public class CommoditySaleDraftEntity {

    @BsonId
    public UUID id;

    /** La saisie, sérialisée telle que le formulaire l'envoie. */
    public String payloadJson;

    // ─── Résumé pour la liste, recopié à chaque enregistrement ───
    public LocalDate date;
    public UUID customerId;
    public String customerName;
    public UUID articleId;
    public String articleName;
    public UUID siteId;
    public String siteName;
    public BigDecimal declaredKg;
    public BigDecimal acceptedKg;
    public List<String> dispatchNoteRefs = new ArrayList<>();

    // ─── Audit ───
    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;
    public String updatedByEmail;

    public CommoditySaleDraftEntity() {}
}
