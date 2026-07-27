package com.ntech.cabosse.members.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Ligne d'import de membre, telle que lue du fichier (parsing côté client).
 * Tous les champs sont des chaînes : la conversion et les contrôles se font
 * au moment de l'aperçu, pour que chaque anomalie soit rattachée à sa ligne.
 */
@Schema(description = "Ligne d'import de membre-producteur")
public record MemberImportRowDto(
        int rowNumber,
        String code,
        String externalCodeType,
        String externalCode,
        String lastName,
        String firstName,
        String gender,
        String personType,
        String maritalStatus,
        String birthDate,
        String birthYear,
        String birthPlace,
        String idDocType,
        String idDocNumber,
        String nationalIdNumber,
        String phone,
        String email,
        String village,
        String section,
        String joinedAt,
        String partsSocialesAmount,
        String paymentMethod,
        String mobileMoneyNumber,
        String spousesCount,
        String childrenCount,
        String girlsCount,
        String boysCount,
        String children0to4,
        String children5to17,
        String childrenOver17,
        String childrenSchooled,
        String childrenNotSchooled,
        String childrenActivity,
        String censusRegistered,
        String producerCardIssued,
        String dataCollectedAt,
        String notes
) {}
