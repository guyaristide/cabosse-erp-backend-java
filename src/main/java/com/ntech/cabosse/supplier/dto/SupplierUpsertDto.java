package com.ntech.cabosse.supplier.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture fournisseur")
public record SupplierUpsertDto(
        @Pattern(regexp = "^$|^[a-z0-9-]{2,40}$", message = "Code en minuscules / chiffres / tirets, 2 à 40 caractères")
        String code,

        @NotBlank(message = "Nom requis")
        @Size(min = 2, max = 120)
        String name,

        @Size(max = 150) String legalName,
        @Size(max = 60)  String taxNumber,

        @Pattern(regexp = "^$|^.+@.+\\..+$", message = "Adresse e-mail invalide")
        @Size(max = 120) String email,

        @Pattern(regexp = "^$|^\\+?[\\d\\s()-]{6,25}$", message = "Téléphone invalide")
        @Size(max = 25)  String phone,

        @Size(max = 250) String addressLine,
        @Size(max = 80)  String cityName,
        @Size(max = 2)   String countryCode,
        @Size(max = 120) String contactName,
        @Size(max = 120) String paymentTerms,
        @Size(max = 1000) String notes
) {}
