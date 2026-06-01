package com.ntech.cabosse.sale.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Résultat d'un import vente. Permet au front d'afficher un récap clair :
 * combien de référentiels ont été créés (et lesquels) avant que la vente
 * elle-même soit enregistrée.
 *
 * <p>Si {@code skipped == true}, la vente n'a pas été créée (doublon
 * détecté sur {@code invoiceNumber}) et {@code sale} est {@code null} ;
 * {@code existingSaleRef} / {@code existingSaleId} pointent vers la vente
 * déjà en base. Aucun référentiel n'est créé dans ce cas.</p>
 */
@Schema(description = "Résultat d'un import vente")
public record SaleImportResultDto(

        /** La vente créée in fine. {@code null} si {@code skipped == true}. */
        SaleResponseDto sale,

        /** {@code true} si le client a été créé pour cet import. */
        boolean customerCreated,
        /** Id (et nom) du client — créé ou existant. {@code null} si skipped. */
        UUID customerId,
        String customerName,

        /** Articles créés à la volée pendant l'import. Vide si tout existait. */
        List<CreatedArticleRef> createdArticles,

        /** {@code true} si la vente a été sautée (doublon facture). */
        boolean skipped,
        /** Raison du saut (présent uniquement si {@code skipped}). */
        String skippedReason,
        /** Référence de la vente déjà en base ayant le même n° facture. */
        String existingSaleRef,
        /** Id de la vente déjà en base ayant le même n° facture. */
        UUID existingSaleId

) {

    public record CreatedArticleRef(UUID id, String code, String name) {}

    /** Constructeur de commodité : import réussi (pas de skip). */
    public static SaleImportResultDto created(
            SaleResponseDto sale,
            boolean customerCreated,
            UUID customerId,
            String customerName,
            List<CreatedArticleRef> createdArticles
    ) {
        return new SaleImportResultDto(
                sale, customerCreated, customerId, customerName, createdArticles,
                false, null, null, null
        );
    }

    /** Constructeur de commodité : doublon facture, vente non créée. */
    public static SaleImportResultDto skipped(
            String invoiceNumber, String existingRef, UUID existingId
    ) {
        return new SaleImportResultDto(
                null, false, null, null, List.of(),
                true,
                "Facture " + invoiceNumber + " déjà importée (vente " + existingRef + ")",
                existingRef, existingId
        );
    }
}
