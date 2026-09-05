package com.ntech.cabosse.supplier.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture fournisseur")
public record SupplierUpsertDto(
        @Pattern(regexp = "^$|^[a-z0-9-]{2,40}$", message = "{v.code-en-minuscules-chiffres-tirets-2-a-40-caracteres}")
        String code,

        @NotBlank(message = "{v.nom-requis}")
        @Size(min = 2, max = 120)
        String name,

        @Size(max = 150) String legalName,
        @Size(max = 60)  String taxNumber,

        @Pattern(regexp = "^$|^.+@.+\\..+$", message = "{v.adresse-e-mail-invalide}")
        @Size(max = 120) String email,

        @Pattern(regexp = "^$|^\\+?[\\d\\s()-]{6,25}$", message = "{v.telephone-invalide}")
        @Size(max = 25)  String phone,

        @Size(max = 250) String addressLine,
        @Size(max = 80)  String cityName,
        @Size(max = 2)   String countryCode,
        @Size(max = 120) String contactName,
        @Size(max = 120) String paymentTerms,
        @Size(max = 1000) String notes,

        /** Délégué collecteur (backlog ACH-02). */
        Boolean collector,
        /**
         * Section rattachée au délégué.
         *
         * <p>Conservée pour les structures qui n'ont pas encore rangé leurs
         * localités. Dès que {@code localityIds} est renseigné, la section
         * en est dérivée et cette valeur est ignorée.</p>
         */
        java.util.UUID sectionId,

        /** Localités où le délégué collecte. Une localité n'a qu'un délégué. */
        java.util.List<java.util.UUID> localityIds,
        /** Taux de rémunération propre au délégué. Vide : taux du tenant. */
        @jakarta.validation.constraints.DecimalMin(value = "0", message = "{v.taux-negatif-interdit}")
        java.math.BigDecimal collectorMarginRate,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^[0-9]{3,20}$",
                message = "{v.numero-de-compte-invalide}")
        @org.eclipse.microprofile.openapi.annotations.media.Schema(
                description = "Compte comptable d'avance propre à ce tiers, ouvert par la "
                        + "structure dans son plan. Absent : l'écriture retombe sur le compte "
                        + "collectif.", example = "409101")
        String advanceAccount,


        /**
         * Mise en compte : retenue en FCFA/kg sur chaque livraison. Usage
         * courant entre 10 et 35 FCFA/kg, sans borne dure : une entente
         * hors fourchette reste une entente.
         */
        @jakarta.validation.constraints.DecimalMin(value = "0", message = "{v.mise-en-compte-negative-interdite}")
        java.math.BigDecimal collectorRetentionPerKg,
        /** Catégorie de reprise (backlog ACH-07). Vide : aucune. */
        java.util.UUID categoryId
) {}
