package com.ntech.cabosse.dispatch.entity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Une ligne du bordereau de sortie (CE-195) : l'appel d'un bordereau de
 * réception, au besoin partiel. Le net seul sort du stock ; le brut se
 * déduit du net et des sacs (brut = net + sacs, la règle du carnet), le
 * lot du reçu suit la ligne pour que la traçabilité aval soit exacte.
 */
public class DispatchLine {

    /** Le reçu d'achat appelé (le « BR » du carnet). */
    public UUID receiptId;
    public String receiptRef;
    /** Bordereau de livraison du reçu, quand il en a un : c'est le lot. */
    public String lotRef;

    public BigDecimal grossKg;
    public Integer bagsCount;
    public BigDecimal netKg;

    /** CMUP photographié à la sortie, pour un coût des ventes exact. */
    public BigDecimal cmupAtDispatch;

    public DispatchLine() {}
}
