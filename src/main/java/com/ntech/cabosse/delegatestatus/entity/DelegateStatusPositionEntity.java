package com.ntech.cabosse.delegatestatus.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Position prise sur un délégué à une date donnée (backlog DEL-01, DEL-03).
 *
 * <p>Écrite en ajout, jamais en modification. Un champ unique sur la fiche
 * écraserait la valeur précédente à chaque changement : on perdrait qui a
 * décidé, quand et pourquoi, c'est-à-dire tout ce qui fait qu'une position
 * se défend. Le statut courant d'un délégué est simplement sa position la
 * plus récente à la date du jour.</p>
 *
 * <p>La position vit ici et non sur une opération : un délégué douteux est
 * justement celui sur lequel plus aucune opération n'est saisie, donc un
 * marquage qui passerait par une transaction ne l'atteindrait jamais.</p>
 *
 * <p>{@link #owedAmount} est calculé et écrit par le serveur au moment de
 * la prise de position, jamais saisi : un montant tapé par l'utilisateur ne
 * prouverait rien. C'est un constat daté, il ne bouge plus ensuite, et
 * c'est son écart avec le dû du jour qui dit si quelque chose a été
 * recouvré depuis.</p>
 */
public class DelegateStatusPositionEntity {

    @BsonId
    public UUID id;

    /** Le délégué concerné, un fournisseur porteur du drapeau collecteur. */
    public UUID delegateSupplierId;

    /** La position tenue, référencée dans le référentiel du tenant. */
    public UUID statusId;

    /** Recopié à l'écriture : l'historique reste lisible si le libellé change. */
    public String statusCode;
    public String statusLabel;

    /** Date à laquelle la position commence à valoir. */
    public LocalDate effectiveDate;

    /** Ce qui motive la décision. Obligatoire : une position nue ne se défend pas. */
    public String reason;

    /** Ce que le délégué devait à la coopérative au moment de la prise de position. */
    public BigDecimal owedAmount;

    /** Campagne en cours à ce moment, pour situer le montant figé. */
    public UUID campaignId;
    public String campaignLabel;

    public Instant createdAt;
    public UUID createdBy;
    public String createdByEmail;
}
