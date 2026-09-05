package com.ntech.cabosse.sale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payload d'import de vente depuis un fichier Excel parsé côté client. Le
 * front lit le tableur (xlsx), regroupe les lignes par numéro de facture,
 * traduit les libellés FR (statut, paiement, canal) et envoie un payload
 * structuré ; le serveur orchestre la résolution des référentiels
 * (client, articles produits finis, site) et chaîne les transitions de la
 * vente pour atteindre le statut cible.
 *
 * <p>Symétrique de {@link com.ntech.cabosse.achats.dto.PurchaseOrderImportDto}
 * pour les achats. Aucune logique de parsing fichier ici : le DTO est le
 * <em>contrat backend</em>, indépendant des colonnes brutes du tableur.</p>
 *
 * <p><strong>Dédoublonnage</strong> : si {@link #invoiceNumber()} est
 * renseigné et matche une vente existante (case-insensitive), l'import est
 * sauté et aucun référentiel n'est créé. Les lignes sans numéro de facture
 * sont toujours importées (chaque ligne du fichier devient alors une vente
 * isolée côté front).</p>
 */
@Schema(description = "Payload d'import de vente depuis fichier Excel parsé côté client")
public record SaleImportDto(

        @NotNull(message = "{v.client-requis}")
        @Valid
        ImportedCustomer customer,

        /**
         * Site, résolu par nom (case-insensitive) côté serveur. Le front
         * envoie le libellé brut du fichier (ex : « Méagui »). Si vide ou
         * non résolu, le serveur fallback au site courant (TenantContext).
         */
        @NotBlank(message = "{v.nom-de-site-requis}")
        @Size(max = 120)
        String siteName,

        /**
         * Canal de distribution du client (raw enum CustomerChannelType).
         * Si client à créer, ce canal est posé sur la fiche client ; sinon
         * ignoré (on garde le canal existant). Sert aussi à dériver le
         * canal de vente B2B/B2C.
         */
        @Pattern(regexp = "^$|^(GMS|HOTELLERIE|B2B|B2C|RETAIL|OTHER)$",
                message = "{v.canal-autorise-gms-hotellerie-b2b-b2c-retail-other}")
        String channelType,

        @NotNull(message = "{v.date-de-vente-requise}")
        LocalDate saleDate,

        @Size(max = 80, message = "{v.numero-de-facture-trop-long}")
        String invoiceNumber,

        /**
         * Mode de paiement raw — l'un de {@code CASH | MOBILE_MONEY |
         * BANK_TRANSFER | CHECK} ou {@code null}. CHECK est mappé à
         * {@code OTHER} côté domaine (l'énum {@link
         * com.ntech.cabosse.reception.entity.PaymentMethod} ne distingue
         * pas chèque vs autre).
         */
        @Pattern(regexp = "^$|^(CASH|MOBILE_MONEY|BANK_TRANSFER|CHECK)$",
                message = "{v.methode-autorisee-cash-mobile-money-bank-transfer-check}")
        String paymentMethod,

        /**
         * Montant déjà payé (FCFA, snapshot du fichier). {@code 0} ou
         * {@code null} = aucun paiement enregistré. Si {@code > 0}, un
         * versement est créé après la transition de statut. Plafonné
         * implicitement par {@link com.ntech.cabosse.sale.service.SaleService#recordPayment}
         * (qui refuse les paiements > reste dû).
         */
        @DecimalMin(value = "0", message = "{v.montant-paye-negatif-interdit}")
        BigDecimal totalPaid,

        /**
         * Statut cible après import. {@code null}/QUOTE/DRAFT = devis créé,
         * pas de transition. CONFIRMED valide le devis. DELIVERED valide
         * puis livre (mouvements stock OUT). CANCELLED valide puis annule.
         */
        @Pattern(regexp = "^$|^(DRAFT|QUOTE|CONFIRMED|DELIVERED|CANCELLED)$",
                message = "{v.statut-autorise-draft-quote-confirmed-delivered-cancelled}")
        String initialStatus,

        /**
         * Si {@code true}, refuse de créer à la volée client ou article.
         * Tout référentiel inconnu fait échouer l'import. Évite les
         * doublons d'orthographe lors d'un import massif.
         */
        Boolean strictMode,

        @NotEmpty(message = "{v.au-moins-une-ligne-requise}")
        List<@Valid ImportedLine> lines

) {

    /**
     * Client existant ({@code id} renseigné) ou à créer ({@code name}
     * requis, le reste optionnel).
     */
    @Schema(description = "Client de l'import : existant ou à créer")
    public record ImportedCustomer(

            UUID id,

            @Size(min = 2, max = 120, message = "{v.nom-entre-2-et-120-caracteres}")
            String name,

            /** Canal de distribution — appliqué uniquement si création. */
            @Pattern(regexp = "^$|^(GMS|HOTELLERIE|B2B|B2C|RETAIL|OTHER)$",
                    message = "{v.canal-autorise-gms-hotellerie-b2b-b2c-retail-other}")
            String channelType,

            @Size(max = 120) String email,
            @Size(max = 40)  String phone,
            @Size(max = 250) String addressLine,
            @Size(max = 80)  String cityName,
            @Size(max = 2)   String countryCode,
            @Size(max = 120) String contactName

    ) {}

    /**
     * Ligne de vente importée. Soit l'article existe déjà
     * ({@code articleId} renseigné), soit il doit être créé à la volée
     * ({@code newArticle} renseigné) — toujours de type
     * {@code FINISHED_PRODUCT} pour une vente.
     */
    @Schema(description = "Ligne d'import vente : match ou auto-création article")
    public record ImportedLine(

            /** Article existant. Null si auto-création. */
            UUID articleId,

            /** Article à créer. Null si {@code articleId} renseigné. */
            @Valid
            ImportedArticle newArticle,

            @NotNull(message = "{v.quantite-requise}")
            @Positive(message = "{v.quantite-doit-etre-0}")
            BigDecimal quantity,

            @NotNull(message = "{v.prix-unitaire-requis}")
            @DecimalMin(value = "0", message = "{v.prix-negatif-interdit}")
            BigDecimal unitPrice,

            /** Remise ligne 0..100. Optionnel. */
            @DecimalMin(value = "0", message = "{v.remise-negative-interdite}")
            BigDecimal discountPct

    ) {}

    /**
     * Article à créer à la volée — toujours {@code FINISHED_PRODUCT}
     * côté import vente (le type n'est pas paramétré). Le code est
     * facultatif : auto-dérivé du nom par {@link
     * com.ntech.cabosse.article.service.ArticleService#create} sinon.
     */
    @Schema(description = "Article à créer durant l'import vente (FINISHED_PRODUCT)")
    public record ImportedArticle(

            @NotBlank(message = "{v.nom-de-l-article-requis}")
            @Size(min = 2, max = 120)
            String name,

            @Size(max = 40)
            String code,

            @NotBlank(message = "{v.unite-requise}")
            @Size(max = 20)
            String unit

    ) {}
}
