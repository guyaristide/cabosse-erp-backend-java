package com.ntech.cabosse.customer.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Payload d'écriture client")
public record CustomerUpsertDto(
        @Pattern(regexp = "^$|^[a-z0-9-]{2,40}$")
        String code,

        @NotBlank @Size(min = 2, max = 120)
        String name,

        @NotBlank @Pattern(regexp = "^(INDIVIDUAL|COMPANY)$",
                message = "Type autorisé : INDIVIDUAL | COMPANY")
        String type,

        /**
         * Canal de distribution (optionnel). Vide ou {@code null} =
         * non renseigné ; sera traité comme « Autre » à l'affichage
         * humanisé.
         */
        @Pattern(regexp = "^$|^(GMS|HOTELLERIE|B2B|B2C|RETAIL|OTHER)$",
                message = "Canal autorisé : GMS | HOTELLERIE | B2B | B2C | RETAIL | OTHER")
        String channelType,

        @Size(max = 150) String legalName,
        @Size(max = 60)  String taxNumber,

        @Pattern(regexp = "^$|^.+@.+\\..+$")
        @Size(max = 120) String email,

        @Pattern(regexp = "^$|^\\+?[\\d\\s()-]{6,25}$")
        @Size(max = 25)  String phone,

        @Size(max = 250) String addressLine,
        @Size(max = 80)  String cityName,
        @Size(max = 2)   String countryCode,
        @Size(max = 120) String contactName,

        @DecimalMin(value = "0", message = "Plafond de crédit négatif interdit")
        BigDecimal creditLimit,

        @Size(max = 1000) String notes
) {}
