package com.ntech.cabosse.members.dto;

import com.ntech.cabosse.members.entity.MemberCivilStatus;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberStatus;

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
        LocalDate birthDate,
        Integer birthYear,
        String idDocType,
        String idDocNumber,
        UUID idCardFileId,
        UUID sectionId,
        UUID followUpAgentMemberId,
        List<UUID> deliveredArticleIds,
        List<MemberExternalCodeDto> externalProducerCodes,
        String village,
        String phone,
        String email,
        LocalDate joinedAt,
        BigDecimal partsSocialesAmount,
        MemberStatus status,
        UUID supplierId,
        List<UUID> parcels,
        String preferredPaymentMethod,
        String mobileMoneyNumber,
        String notes,
        String statusReason,
        Instant approvedAt,
        List<DocumentView> documents,
        Instant createdAt,
        Instant updatedAt
) {
    public record DocumentView(UUID id, String label, String fileName,
                               String mimeType, long sizeBytes, Instant uploadedAt) {}

    public static MemberResponseDto from(MemberEntity e) {
        return new MemberResponseDto(
                e.id, e.code, e.name, e.firstName, e.lastName, e.civilStatus,
                e.birthDate, e.birthYear, e.idDocType, e.idDocNumber, e.idCardFileId,
                e.sectionId, e.followUpAgentMemberId,
                e.deliveredArticleIds != null ? List.copyOf(e.deliveredArticleIds) : List.of(),
                e.externalProducerCodes == null ? List.of() : e.externalProducerCodes.stream()
                        .map(MemberExternalCodeDto::from).toList(),
                e.village, e.phone, e.email,
                e.joinedAt, e.partsSocialesAmount, e.status,
                e.supplierId,
                e.parcels != null ? List.copyOf(e.parcels) : List.of(),
                e.preferredPaymentMethod, e.mobileMoneyNumber, e.notes,
                e.statusReason, e.approvedAt,
                e.documents == null ? List.of() : e.documents.stream()
                        .map(d -> new DocumentView(d.id, d.label, d.fileName,
                                d.mimeType, d.sizeBytes, d.uploadedAt))
                        .toList(),
                e.createdAt, e.updatedAt
        );
    }
}
