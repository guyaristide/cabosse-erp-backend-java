package com.ntech.cabosse.members.dto;

import com.ntech.cabosse.members.entity.MemberCivilStatus;
import com.ntech.cabosse.members.entity.MemberStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payload de création / mise à jour d'un membre (backlog MEM-06).
 *
 * <p>Le nom est saisi en deux champs {@code lastName} / {@code firstName},
 * recomposés en {@code name} par le service. {@code name} reste accepté en
 * entrée pour rétrocompatibilité : fourni sans {@code lastName}, il alimente
 * {@code lastName}. Le service exige au moins l'un des deux.</p>
 */
public record MemberUpsertDto(
        // Code interne producteur ; généré MB-YYYY-NNNN si vide (création uniquement).
        @Size(max = 40) String code,

        // Rétrocompat : nom complet legacy. Préférer lastName / firstName.
        @Size(max = 120) String name,
        @Size(max = 120) String lastName,
        @Size(max = 120) String firstName,

        @NotNull MemberCivilStatus civilStatus,

        LocalDate birthDate,
        Integer birthYear,

        @Size(max = 120) String idDocType,
        @Size(max = 80) String idDocNumber,
        UUID idCardFileId,

        UUID sectionId,
        UUID followUpAgentMemberId,
        List<UUID> deliveredArticleIds,
        List<@Valid MemberExternalCodeDto> externalProducerCodes,

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
