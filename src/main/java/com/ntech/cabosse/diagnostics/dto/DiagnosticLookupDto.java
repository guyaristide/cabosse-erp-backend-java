package com.ntech.cabosse.diagnostics.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** Résultat d'une recherche de pièce dans la base d'un tenant. */
@Schema(description = "Pièce trouvée et tout ce qui s'y rattache")
public record DiagnosticLookupDto(
        String tenantName,
        String query,
        /**
         * La pièce trouvée d'abord, puis ce qu'elle a produit ou ce dont
         * elle provient : pièce comptable, mouvement de stock, bordereau,
         * avance consommée.
         */
        List<DiagnosticDocumentDto> documents
) {
}
