package com.ntech.cabosse.production.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Résultat d'un import OF. Référence l'OF créé + trace des articles
 * auto-créés (PF + matières/consommables) pour permettre au front
 * d'afficher un rapport « ce qui vient d'être créé ».
 */
@Schema(description = "Résultat d'un import d'ordre de fabrication")
public record ManufacturingOrderImportResultDto(

        ProductionOrderResponseDto manufacturingOrder,

        /** Vrai si le PF a été auto-créé à cet import. */
        boolean finishedProductCreated,
        UUID finishedProductId,
        String finishedProductName,

        /** Articles consommés auto-créés (ceux déjà existants n'apparaissent pas). */
        List<CreatedArticleRef> createdConsumables

) {
    public record CreatedArticleRef(UUID id, String code, String name, String type) {}
}
