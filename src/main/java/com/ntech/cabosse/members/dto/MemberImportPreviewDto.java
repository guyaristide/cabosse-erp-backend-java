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
        List<Row> rows
) {
    public enum Status { READY, UPDATE, WARNING, INVALID, DUPLICATE_IN_FILE }

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
            String notes
    ) {}

    public record FieldIssue(String field, String message) {}
}
