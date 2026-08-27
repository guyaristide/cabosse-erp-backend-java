package com.ntech.cabosse.members.service;

/**
 * Informations attendues dans un dossier producteur, désignées par un code
 * stable plutôt que par leur intitulé.
 *
 * <p>La complétude renvoyait au client des libellés français prêts à
 * afficher. Deux ennuis en découlaient. Le front ne pouvait pas les
 * traduire, et il devait les <em>comparer</em> pour savoir quel bloc de la
 * fiche marquer « à compléter » : le contrat reposait donc sur
 * l'orthographe exacte d'une phrase française, qu'une reformulation
 * anodine aurait cassée en silence. Et le message qui refuse un reçu
 * d'achat pour dossier incomplet, lui, était traduit mais y interpolait
 * ces intitulés, si bien qu'un utilisateur anglophone lisait une phrase
 * moitié anglaise moitié française.</p>
 *
 * <p>Le code est ce que l'API transporte, l'intitulé se fabrique au
 * moment de l'affichage, dans la langue demandée.</p>
 */
public enum MemberFileField {

    LAST_NAME("m.mbr-field-lastName"),
    FIRST_NAMES("m.mbr-field-firstNames"),
    GENDER("m.mbr-field-gender"),
    BIRTH_DATE("m.mbr-field-birthDate"),
    BIRTH_PLACE("m.mbr-field-birthPlace"),
    IDENTITY_DOCUMENT("m.mbr-field-identityDocument"),
    PHONE("m.mbr-field-phone"),
    VILLAGE("m.mbr-field-village"),
    SECTION("m.mbr-field-section"),
    PARCEL("m.mbr-field-parcel"),
    HOUSEHOLD("m.mbr-field-household"),
    CENSUS("m.mbr-field-census"),
    COLLECTION_DATE("m.mbr-field-collectionDate"),
    TRADE_REGISTER("m.mbr-field-tradeRegister"),
    LEGAL_REPRESENTATIVE("m.mbr-field-legalRepresentative");

    private final String messageKey;

    MemberFileField(String messageKey) {
        this.messageKey = messageKey;
    }

    /** Clé de catalogue portant l'intitulé du champ. */
    public String messageKey() {
        return messageKey;
    }
}
