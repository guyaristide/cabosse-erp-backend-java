package com.ntech.cabosse.achats.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payload d'import de BC depuis un fichier (CSV ou Excel) parsé côté
 * client. Le client fait le mapping colonnes → champs, marque les
 * référentiels manquants comme à créer (par valeur de défaut), et envoie
 * un payload structuré au serveur qui se contente d'orchestrer la
 * création en transaction.
 *
 * <p>Cf. {@code project-m2-import-decisions} (mémoire conversation) pour
 * le scope verrouillé.</p>
 *
 * <p><strong>WIP</strong> : la structure des colonnes côté fichier
 * dépend du fichier exemple que l'utilisateur va fournir — ce DTO est le
 * <em>contrat backend</em>, indépendant des colonnes brutes. Le mapping
 * client → DTO vit côté front.</p>
 */
@Schema(description = "Payload d'import BC depuis fichier CSV/Excel parsé côté client")
public record PurchaseOrderImportDto(

        /**
         * Fournisseur — soit existant (id renseigné), soit à créer (les
         * autres champs sont alors lus). Le serveur vérifie que les deux
         * modes ne sont pas mélangés sur un même payload.
         */
        @Valid
        @NotNull(message = "Fournisseur requis")
        ImportedSupplier supplier,

        @NotNull(message = "Date de commande requise")
        LocalDate orderDate,

        LocalDate deliveryDate,
        LocalDate invoiceDate,

        @Size(max = 80, message = "Numéro de facture trop long")
        String invoiceNumber,

        @Size(max = 120)
        String paymentTerms,

        @NotEmpty(message = "Au moins une ligne requise")
        List<@Valid ImportedLine> lines,

        @DecimalMin(value = "0", message = "Transport négatif interdit")
        BigDecimal transportFcfa,

        @NotNull(message = "Taux de TVA requis")
        @DecimalMin(value = "0", message = "TVA négative interdite")
        @DecimalMax(value = "100", message = "TVA > 100 % interdite")
        BigDecimal vatRatePct,

        @Size(max = 2000)
        String notes

) {

    /**
     * Ligne de BC en provenance d'un import. Soit l'article existe déjà
     * ({@code articleId} renseigné), soit il doit être créé à la volée
     * ({@code newArticle} renseigné).
     */
    @Schema(description = "Ligne d'import — match ou auto-création article")
    public record ImportedLine(

            /** Article existant. Null si le client veut auto-créer. */
            UUID articleId,

            /** Article à créer. Null si {@code articleId} renseigné. */
            @Valid
            ImportedArticle newArticle,

            @NotNull(message = "Quantité requise")
            @DecimalMin(value = "0", inclusive = false, message = "Quantité doit être > 0")
            BigDecimal quantity,

            @NotNull(message = "Prix unitaire requis")
            @DecimalMin(value = "0")
            BigDecimal unitPriceFcfa,

            @DecimalMin(value = "0")
            @DecimalMax(value = "100")
            BigDecimal discountPct

    ) {}

    /**
     * Fournisseur existant ({@code id} renseigné) ou à créer
     * ({@code name} requis, le reste optionnel).
     */
    @Schema(description = "Fournisseur de l'import — existant ou à créer")
    public record ImportedSupplier(

            UUID id,

            @Size(min = 2, max = 120, message = "Nom entre 2 et 120 caractères")
            String name,

            @Size(max = 200)
            String legalName,

            @Size(max = 200)
            String addressLine,

            @Size(max = 80)
            String cityName,

            @Size(max = 2)
            String countryCode,

            @Size(max = 80)
            String contactName,

            @Size(max = 120)
            String email,

            @Size(max = 40)
            String phone,

            @Size(max = 120)
            String paymentTerms

    ) {}

    /**
     * Article à créer à la volée — typiquement {@code RAW_MATERIAL}
     * (matière première) par défaut puisqu'on est en achat. Le code est
     * facultatif : auto-dérivé du nom sinon.
     */
    @Schema(description = "Article à créer durant l'import")
    public record ImportedArticle(

            @NotBlank(message = "Nom de l'article requis")
            @Size(min = 2, max = 120)
            String name,

            @Size(max = 40)
            String code,

            /** {@code RAW_MATERIAL} | {@code CONSUMABLE} | {@code PACKAGING}. */
            @NotBlank(message = "Type de l'article requis")
            String type,

            @NotBlank(message = "Unité requise")
            @Size(max = 20)
            String unit,

            @Size(max = 60)
            String activityCode

    ) {}
}
