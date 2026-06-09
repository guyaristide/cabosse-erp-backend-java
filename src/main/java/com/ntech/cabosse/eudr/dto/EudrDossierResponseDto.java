package com.ntech.cabosse.eudr.dto;

import com.ntech.cabosse.eudr.entity.EudrDossierEntity;
import com.ntech.cabosse.eudr.entity.EudrRiskLevel;
import com.ntech.cabosse.eudr.entity.EudrStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EudrDossierResponseDto(
        UUID id,
        UUID parcelId,
        String parcelCode,
        String parcelName,
        EudrStatus status,
        EudrRiskLevel riskLevel,
        List<EudrDocumentDto> documents,
        Instant lastReviewedAt,
        String lastReviewedByEmail,
        LocalDate complianceExpiresOn,
        String exclusionReason,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
    public static EudrDossierResponseDto from(EudrDossierEntity e) {
        return new EudrDossierResponseDto(
                e.id, e.parcelId, e.parcelCode, e.parcelName,
                e.status, e.riskLevel,
                e.documents != null
                        ? e.documents.stream().map(EudrDocumentDto::from).toList()
                        : List.of(),
                e.lastReviewedAt, e.lastReviewedByEmail,
                e.complianceExpiresOn, e.exclusionReason, e.notes,
                e.createdAt, e.updatedAt
        );
    }
}
