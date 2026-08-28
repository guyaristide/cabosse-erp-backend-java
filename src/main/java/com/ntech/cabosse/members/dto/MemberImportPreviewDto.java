package com.ntech.cabosse.members.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Aperçu d'un import de membres, ligne par ligne.
 *
 * @param updateRows  lignes rapprochées d'un membre existant : elles le
 *                    mettront à jour au lieu d'en créer un second
 * @param warningRows lignes dont le ménage est incohérent : écartées par
 *                    défaut, importables si l'utilisateur passe outre
 */
@Schema(description = "Résultat de l'aperçu d'import de membres")
public record MemberImportPreviewDto(
        int totalRows, int readyRows, int updateRows, int warningRows,
        int invalidRows, int duplicateRows,
        /** Lignes qui n'ajoutent qu'une parcelle à un producteur déjà vu. */
        int additionalParcelRows,
        /** Parcelles que le fichier créera, toutes lignes confondues. */
        int parcelsToCreate,
        /** Parcelles que le fichier mettra à jour, reconnues par leur code. */
        int parcelsToUpdate,
        List<Row> rows
) {
    public enum Status {
        READY, UPDATE, WARNING, INVALID, DUPLICATE_IN_FILE,
        /**
         * Ligne supplémentaire d'un producteur déjà porté plus haut dans le
         * fichier : elle n'apporte que sa parcelle.
         *
         * <p>C'est la façon dont un producteur à plusieurs parcelles se
         * déclare : plusieurs lignes portant le même code. Sans ce statut,
         * la deuxième ligne serait rejetée comme un doublon.</p>
         */
        ADDITIONAL_PARCEL
    }

    /**
     * @param matchedMemberId membre existant reconnu, le cas échéant
     * @param matchedOn       critère ayant permis le rapprochement, affiché
     *                        à l'utilisateur pour qu'il puisse le contester
     */
    public record Row(
            int rowNumber, Status status, Normalized normalized,
            UUID matchedMemberId, String matchedOn,
            List<FieldIssue> issues
    ) {}

    public record Normalized(
            String code, String name, String firstName, String lastName,
            String gender, String personType, String maritalStatus,
            String birthDate, Integer birthYear, String birthPlace,
            String idDocType, String idDocNumber, String nationalIdNumber,
            String externalCodeType, String externalCode,
            String phone, String email, String village, String sectionName,
            String joinedAt, java.math.BigDecimal partsSocialesAmount,
            String paymentMethod, String mobileMoneyNumber,
            Integer spousesCount, Integer childrenCount, Integer girlsCount, Integer boysCount,
            Integer children0to4, Integer children5to17, Integer childrenOver17,
            Integer childrenSchooled, Integer childrenNotSchooled, String childrenActivity,
            Boolean censusRegistered, Boolean producerCardIssued, String dataCollectedAt,
            String notes,
            /** Parcelle portée par la ligne, null si la ligne n'en porte pas. */
            Parcel parcel,
            /** Code du délégué collecteur rattaché au producteur, le cas échéant. */
            String delegateCode
    ) {}

    /**
     * Parcelle lue sur la ligne du producteur.
     *
     * <p>{@code code} vide : la parcelle sera créée. Rempli : elle sera
     * mise à jour. C'est ce qui évite qu'un réimport double la superficie
     * de la coopérative.</p>
     */
    public record Parcel(
            String code, String name, java.math.BigDecimal surfaceHa,
            java.math.BigDecimal potentialKg, String cropCode, String variety,
            Integer plantingYear, Double latitude, Double longitude,
            String regionCode, String departmentCode, String status,
            List<String> certifications,
            /** Parcelle déjà connue, retrouvée par son code. */
            UUID matchedParcelId
    ) {}

    public record FieldIssue(String field, String message) {}
}
