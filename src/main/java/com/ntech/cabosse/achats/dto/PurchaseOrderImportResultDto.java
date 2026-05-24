package com.ntech.cabosse.achats.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Résultat d'un import BC. Permet au front d'afficher un récap clair :
 * combien de référentiels ont été créés (et lesquels) avant que le BC
 * lui-même soit enregistré.
 */
@Schema(description = "Résultat d'un import BC")
public record PurchaseOrderImportResultDto(

        /** Le BC créé in fine. */
        PurchaseOrderResponseDto purchaseOrder,

        /** {@code true} si le fournisseur a été créé pour cet import. */
        boolean supplierCreated,
        /** Id (et nom) du fournisseur — créé ou existant. */
        UUID supplierId,
        String supplierName,

        /** Articles créés à la volée pendant l'import. Vide si tout existait. */
        List<CreatedArticleRef> createdArticles

) {

    public record CreatedArticleRef(UUID id, String code, String name, String type) {}
}
