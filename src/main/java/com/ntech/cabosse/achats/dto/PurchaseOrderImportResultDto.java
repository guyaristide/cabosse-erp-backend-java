package com.ntech.cabosse.achats.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Résultat d'un import BC. Permet au front d'afficher un récap clair :
 * combien de référentiels ont été créés (et lesquels) avant que le BC
 * lui-même soit enregistré.
 *
 * <p>Si {@code skipped == true}, le BC n'a pas été créé (doublon détecté
 * sur {@code invoiceNumber}) et {@code purchaseOrder} est {@code null} ;
 * {@code existingBcRef} / {@code existingBcId} pointent vers le BC déjà
 * en base. Aucun référentiel n'est créé dans ce cas.</p>
 */
@Schema(description = "Résultat d'un import BC")
public record PurchaseOrderImportResultDto(

        /** Le BC créé in fine. {@code null} si {@code skipped == true}. */
        PurchaseOrderResponseDto purchaseOrder,

        /** {@code true} si le fournisseur a été créé pour cet import. */
        boolean supplierCreated,
        /** Id (et nom) du fournisseur — créé ou existant. {@code null} si skipped. */
        UUID supplierId,
        String supplierName,

        /** Articles créés à la volée pendant l'import. Vide si tout existait. */
        List<CreatedArticleRef> createdArticles,

        /** {@code true} si le BC a été sauté (doublon facture). */
        boolean skipped,
        /** Raison du saut (présent uniquement si {@code skipped}). */
        String skippedReason,
        /** Référence du BC déjà en base ayant le même n° facture. */
        String existingBcRef,
        /** Id du BC déjà en base ayant le même n° facture. */
        UUID existingBcId

) {

    public record CreatedArticleRef(UUID id, String code, String name, String type) {}

    /** Constructeur de commodité : import réussi (pas de skip). */
    public static PurchaseOrderImportResultDto created(
            PurchaseOrderResponseDto bc,
            boolean supplierCreated,
            UUID supplierId,
            String supplierName,
            List<CreatedArticleRef> createdArticles
    ) {
        return new PurchaseOrderImportResultDto(
                bc, supplierCreated, supplierId, supplierName, createdArticles,
                false, null, null, null
        );
    }

    /** Constructeur de commodité : doublon facture, BC non créé. */
    public static PurchaseOrderImportResultDto skipped(
            String invoiceNumber, String existingRef, UUID existingId
    ) {
        return new PurchaseOrderImportResultDto(
                null, false, null, null, List.of(),
                true,
                "Facture " + invoiceNumber + " déjà importée (BC " + existingRef + ")",
                existingRef, existingId
        );
    }
}
