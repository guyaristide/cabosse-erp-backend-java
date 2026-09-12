package com.ntech.cabosse.diagnostics.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

/**
 * Un document de la base d'un tenant, tel qu'il est réellement stocké.
 *
 * <p>Les champs partent bruts, sans passer par un DTO métier : le
 * diagnostic sert justement à voir ce qu'un DTO ne montre pas.</p>
 */
@Schema(description = "Document brut d'un tenant, pour diagnostic plateforme")
public record DiagnosticDocumentDto(
        /** Collection Mongo d'origine. */
        String collection,
        /** Ce qui dit à quel titre ce document apparaît. */
        String relation,
        Map<String, Object> fields
) {
}
