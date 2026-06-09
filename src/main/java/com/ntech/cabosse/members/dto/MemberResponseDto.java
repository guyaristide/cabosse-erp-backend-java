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
        MemberCivilStatus civilStatus,
        UUID idCardFileId,
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
        Instant createdAt,
        Instant updatedAt
) {
    public static MemberResponseDto from(MemberEntity e) {
        return new MemberResponseDto(
                e.id, e.code, e.name, e.civilStatus, e.idCardFileId,
                e.village, e.phone, e.email,
                e.joinedAt, e.partsSocialesAmount, e.status,
                e.supplierId,
                e.parcels != null ? List.copyOf(e.parcels) : List.of(),
                e.preferredPaymentMethod, e.mobileMoneyNumber, e.notes,
                e.createdAt, e.updatedAt
        );
    }
}
