package com.ntech.cabosse.accounting.controller;

/** Une ligne d'exemple d'un modèle d'import d'écriture type. */
public record TypedOdTemplateRow(String account, String libelle,
                                 String debit, String credit, String tiers) {}
