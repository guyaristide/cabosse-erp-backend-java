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
        UUID existingSaleId,

        /**
         * Le fichier annonçait une livraison, la vente est enregistrée
         * confirmée.
         *
         * <p>Le stock du jour de l'import n'est pas celui du jour de la
         * vente : sortir la matière rétroactivement échouerait sur un
         * stock insuffisant et ferait perdre la ligne entière. La vente
         * s'arrête donc à « confirmée », ce qui est le bon choix, mais se
         * faisait en silence : le stock restait surévalué et rien ne
         * disait quelles ventes livrer à la main (15/09/2026).</p>
         */
        boolean deliveryPostponed

) {

    public record CreatedArticleRef(UUID id, String code, String name) {}

    /** Constructeur de commodité : import réussi (pas de skip). */
    public static SaleImportResultDto created(
            SaleResponseDto sale,
            boolean customerCreated,
            UUID customerId,
            String customerName,
            List<CreatedArticleRef> createdArticles,
            boolean deliveryPostponed
    ) {
        return new SaleImportResultDto(
                sale, customerCreated, customerId, customerName, createdArticles,
                false, null, null, null, deliveryPostponed
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
                existingRef, existingId, false
        );
    }
}
