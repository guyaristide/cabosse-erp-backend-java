package com.ntech.cabosse.members.dto;

import com.ntech.cabosse.members.entity.MemberCivilStatus;
import com.ntech.cabosse.members.entity.MemberStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Payload de création / mise à jour d'un membre. */
public record MemberUpsertDto(
        @NotBlank @Size(min = 2, max = 120) String name,
        @NotNull MemberCivilStatus civilStatus,
        UUID idCardFileId,
        @Size(max = 80) String village,
        @Size(max = 30) String phone,
        @Email @Size(max = 120) String email,
        LocalDate joinedAt,
        BigDecimal partsSocialesAmount,
        @NotNull MemberStatus status,
        @Size(max = 60) String preferredPaymentMethod,
        @Size(max = 30) String mobileMoneyNumber,
        @Size(max = 500) String notes
) {}
