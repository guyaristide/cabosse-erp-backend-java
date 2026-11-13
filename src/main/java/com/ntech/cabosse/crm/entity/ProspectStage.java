package com.ntech.cabosse.crm.entity;

/**
 * Où en est un prospect dans la prospection (SAAS-31).
 *
 * <p>Six étapes, dans l'ordre où elles se vivent. Le passage de l'une à
 * l'autre est libre : un prospect perdu peut revenir, une démo peut se
 * refaire. Le logiciel constate l'étape, il ne la décrète pas.</p>
 */
public enum ProspectStage {
    /** Un premier contact a eu lieu. */
    CONTACTED,
    /** Une démonstration est prévue ou a été faite. */
    DEMO,
    /** Une proposition commerciale a été envoyée. */
    PROPOSAL,
    /** On discute les conditions. */
    NEGOTIATION,
    /** Gagné : la structure se crée, ou est créée. */
    WON,
    /** Perdu, avec son motif. */
    LOST;

    /** Les étapes où la prospection continue : une action y est attendue. */
    public boolean isOpen() {
        return this != WON && this != LOST;
    }
}
