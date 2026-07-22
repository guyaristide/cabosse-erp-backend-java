package com.ntech.cabosse.members.entity;

/**
 * Identifiant externe d'un producteur (backlog MEM-06). Sub-document de
 * {@link MemberEntity}, embarqué dans la liste {@code externalProducerCodes}.
 *
 * <p>Liste extensible plutôt qu'un champ unique en dur : un producteur peut
 * cumuler plusieurs codes (filière, faîtière, programme de certification…).
 * Rester agnostique filière : aucun type imposé, le {@code type} porte le
 * libellé (ex. « Code CCC »), le {@code number} la valeur.</p>
 */
public class MemberExternalCode {

    /** Libellé du type de code (ex. {@code "Code CCC"}). */
    public String type;

    /** Valeur du code externe. */
    public String number;

    public MemberExternalCode() {}

    public MemberExternalCode(String type, String number) {
        this.type = type;
        this.number = number;
    }
}
