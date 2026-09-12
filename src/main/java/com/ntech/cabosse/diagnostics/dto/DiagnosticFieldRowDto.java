package com.ntech.cabosse.diagnostics.dto;

/**
 * Un champ d'un document retrouvé, mis à plat pour l'export.
 *
 * <p>Un document par ligne serait illisible : les champs varient d'une
 * collection à l'autre, et un tableur veut des colonnes stables.</p>
 */
public record DiagnosticFieldRowDto(String collection, String relation,
                                    String field, String value) {
}
