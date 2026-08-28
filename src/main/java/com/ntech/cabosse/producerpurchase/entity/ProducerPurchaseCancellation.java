package com.ntech.cabosse.producerpurchase.entity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Trace d'une contre-passation de reçu d'achat producteur.
 *
 * <p>Le reçu n'est pas modifié : on ne réécrit pas un poids ou un prix
 * déjà entrés en stock et déjà comptabilisés. On annule, et on ressaisit.
 * Ce bloc dit qui a annulé, quand, pourquoi, et ce que l'annulation a
 * effectivement défait, pour qu'un contrôle puisse le vérifier sans
 * relire quatre collections.</p>
 */
public class ProducerPurchaseCancellation {

    /** Motif saisi par l'utilisateur, obligatoire. */
    public String reason;

    public String cancelledByEmail;

    public Instant cancelledAt;

    /** Référence de la pièce de contre-passation, si une pièce existait. */
    public String reversalPieceRef;

    /** Montant recrédité sur l'avance du délégué, le cas échéant. */
    public BigDecimal advanceCreditedBackFcfa;

    /** Montant de retenue restitué au crédit du membre, le cas échéant. */
    public BigDecimal creditRestoredFcfa;

    public ProducerPurchaseCancellation() {}
}
