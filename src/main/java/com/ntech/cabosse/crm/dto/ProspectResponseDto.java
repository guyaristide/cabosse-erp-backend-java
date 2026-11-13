package com.ntech.cabosse.crm.dto;

import com.ntech.cabosse.crm.entity.ProspectEntity;
import com.ntech.cabosse.crm.entity.ProspectStage;
import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Un prospect tel que l'écran le lit. */
public record ProspectResponseDto(
        UUID id,
        String name,
        TenantOrganizationModel organizationModel,
        String industry,
        String country,
        String city,
        String source,
        ProspectStage stage,
        Instant stageChangedAt,
        String lostReason,
        List<ProspectContactDto> contacts,
        String ownerEmail,
        LocalDate nextActionAt,
        String nextActionLabel,
        String notes,
        UUID tenantId,
        /** Le nom de la structure née du prospect, relu au moment de répondre. */
        String tenantName,
        Instant createdAt,
        Instant updatedAt,
        String createdByEmail,
        String updatedByEmail
) {
    public static ProspectResponseDto from(ProspectEntity p, String tenantName) {
        return new ProspectResponseDto(
                p.id, p.name, p.organizationModel, p.industry, p.country, p.city, p.source,
                p.stage, p.stageChangedAt, p.lostReason,
                p.contacts == null ? List.of() : p.contacts.stream().map(ProspectContactDto::from).toList(),
                p.ownerEmail, p.nextActionAt, p.nextActionLabel, p.notes,
                p.tenantId, tenantName,
                p.createdAt, p.updatedAt, p.createdByEmail, p.updatedByEmail);
    }
}
