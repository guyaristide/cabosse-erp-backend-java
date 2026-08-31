package com.ntech.cabosse.members.dto;

import com.ntech.cabosse.members.entity.MemberCivilStatus;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberGender;
import com.ntech.cabosse.members.entity.MemberMaritalStatus;
import com.ntech.cabosse.members.entity.MemberPersonType;
import com.ntech.cabosse.members.entity.MemberStatus;
import com.ntech.cabosse.members.service.MemberFileCompleteness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record MemberResponseDto(
        UUID id,
        String code,
        String name,
        String firstName,
        String lastName,
        MemberCivilStatus civilStatus,
        MemberGender gender,
        MemberPersonType personType,
        MemberMaritalStatus maritalStatus,
        String birthPlace,
        MemberLegalIdentityDto legalIdentity,
        LocalDate birthDate,
        Integer birthYear,
        String idDocType,
        String idDocNumber,
        UUID idCardFileId,
        List<MemberIdentityDocumentDto> identityDocuments,
        MemberHouseholdDto household,
        MemberEnrolmentDto enrolment,
        MemberFileStatusDto fileStatus,
        UUID sectionId,
        boolean collector,
        java.math.BigDecimal collectorMarginRate,
        /** Rémunération convenue campagne par campagne, la plus précise. */
        java.util.List<CampaignMarginView> collectorMarginByCampaign,
        UUID followUpAgentMemberId,
        List<UUID> deliveredArticleIds,
        List<MemberExternalCodeDto> externalProducerCodes,
        String village,
        /** Localité du référentiel, null si le village n'y est pas rattaché. */
        UUID localityId,
        /**
         * Délégué qui collecte chez ce producteur, <strong>déduit</strong>
         * de sa localité. Une localité est gérée par un seul délégué : le
         * lien ne se saisit donc pas, il se lit.
         */
        UUID delegateSupplierId,
        String delegateName,
        String phone,
        String email,
        LocalDate joinedAt,
        BigDecimal partsSocialesAmount,
        MemberStatus status,
        UUID supplierId,
        List<UUID> parcels,
        String preferredPaymentMethod,
        String mobileMoneyNumber,
        String mobileMoneyHolderName,
        boolean mobileMoneyMandateOnFile,
        String notes,
        String statusReason,
        Instant approvedAt,
        List<DocumentView> documents,
        Instant createdAt,
        Instant updatedAt
) {

    public record CampaignMarginView(java.util.UUID campaignId, java.math.BigDecimal rate) {}
    public record DocumentView(UUID id, String label, String fileName,
                               String mimeType, long sizeBytes, Instant uploadedAt) {}

    /**
     * Durée de validité d'une enquête retenue quand l'appelant n'a pas les
     * préférences du tenant sous la main. Le service, lui, passe la valeur
     * paramétrée.
     */
    private static final int DEFAULT_FILE_VALIDITY_MONTHS = 12;

    public static MemberResponseDto from(MemberEntity e) {
        return from(e, DEFAULT_FILE_VALIDITY_MONTHS, null);
    }

    public static MemberResponseDto from(MemberEntity e, int fileValidityMonths,
                                         java.util.Set<String> identityProofTypes) {
        return from(e, fileValidityMonths, identityProofTypes, null, null);
    }

    /**
     * @param delegateSupplierId délégué <strong>déduit</strong> de la localité
     *                           du producteur, résolu par l'appelant. Une
     *                           localité est gérée par un seul délégué : le
     *                           lien se lit, il ne se saisit pas. La liste ne
     *                           le résout pas, pour ne pas payer une requête
     *                           par ligne.
     */
    public static MemberResponseDto from(MemberEntity e, int fileValidityMonths,
                                         java.util.Set<String> identityProofTypes,
                                         UUID delegateSupplierId, String delegateName) {
        return new MemberResponseDto(
                e.id, e.code, e.name, e.firstName, e.lastName, e.civilStatus,
                e.gender, e.personType, e.maritalStatus, e.birthPlace,
                MemberLegalIdentityDto.from(e.legalIdentity),
                e.birthDate, e.birthYear, e.idDocType, e.idDocNumber, e.idCardFileId,
                e.identityDocuments == null ? List.of() : e.identityDocuments.stream()
                        .map(MemberIdentityDocumentDto::from).toList(),
                MemberHouseholdDto.from(e.household),
                MemberEnrolmentDto.from(e.enrolment),
                MemberFileCompleteness.evaluate(e, fileValidityMonths, identityProofTypes),
                e.sectionId, e.collector, e.collectorMarginRate,
                e.collectorMarginByCampaign == null ? java.util.List.of()
                        : e.collectorMarginByCampaign.stream()
                                .map(m -> new CampaignMarginView(m.campaignId, m.rate)).toList(),
                e.followUpAgentMemberId,
                e.deliveredArticleIds != null ? List.copyOf(e.deliveredArticleIds) : List.of(),
                e.externalProducerCodes == null ? List.of() : e.externalProducerCodes.stream()
                        .map(MemberExternalCodeDto::from).toList(),
                e.village, e.localityId, delegateSupplierId, delegateName, e.phone, e.email,
                e.joinedAt, e.partsSocialesAmount, e.status,
                e.supplierId,
                e.parcels != null ? List.copyOf(e.parcels) : List.of(),
                e.preferredPaymentMethod, e.mobileMoneyNumber,
                e.mobileMoneyHolderName, e.mobileMoneyMandateOnFile, e.notes,
                e.statusReason, e.approvedAt,
                e.documents == null ? List.of() : e.documents.stream()
                        .map(d -> new DocumentView(d.id, d.label, d.fileName,
                                d.mimeType, d.sizeBytes, d.uploadedAt))
                        .toList(),
                e.createdAt, e.updatedAt
        );
    }
}
