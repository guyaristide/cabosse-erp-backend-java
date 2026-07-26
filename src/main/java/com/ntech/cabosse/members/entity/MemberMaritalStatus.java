package com.ntech.cabosse.members.entity;

/**
 * Situation matrimoniale du producteur (backlog MEM-07). Champ distinct du
 * genre : la fiche signalétique les collecte séparément, et le nombre
 * d'épouses du bloc ménage n'a de sens qu'avec cette information.
 */
public enum MemberMaritalStatus {
    UNKNOWN,
    SINGLE,
    MARRIED,
    COHABITING,
    WIDOWED,
    DIVORCED
}
