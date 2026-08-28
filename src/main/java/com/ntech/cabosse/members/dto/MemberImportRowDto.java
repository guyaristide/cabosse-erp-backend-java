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
        String notes,

        // ─── Parcelle portée par la ligne (import 3-en-1) ───
        // Un producteur qui exploite plusieurs parcelles est déclaré sur
        // plusieurs lignes portant le même code : il est créé une fois, et
        // chaque ligne ajoute sa parcelle.
        /** Vide à la création, rempli aux imports suivants pour retrouver la parcelle. */
        String parcelCode,
        String parcelName,
        String parcelSurfaceHa,
        String parcelPotentialKg,
        String parcelCrop,
        String parcelVariety,
        String parcelPlantingYear,
        String parcelLatitude,
        String parcelLongitude,
        String parcelRegion,
        String parcelDepartment,
        String parcelStatus,
        String parcelCertifications,

        // ─── Rattachement au délégué collecteur ───
        /** Code du délégué qui collecte chez ce producteur. */
        String delegateCode,

        /**
         * Localité choisie pour cette ligne, quand son village ressemble à
         * un ou plusieurs villages connus sans être identique à aucun.
         *
         * <p>Vide, l'aperçu propose ; renseignée, il applique. C'est la
         * réponse de l'utilisateur à ce que le serveur ne peut pas
         * trancher : une fusion de villages ne se défait pas.</p>
         */
        String localityId
) {}
