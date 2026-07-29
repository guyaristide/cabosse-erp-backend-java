package com.ntech.cabosse.members.dto;

import com.ntech.cabosse.members.entity.MemberCivilStatus;
import com.ntech.cabosse.members.entity.MemberGender;
import com.ntech.cabosse.members.entity.MemberMaritalStatus;
import com.ntech.cabosse.members.entity.MemberPersonType;
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
 * Payload de création / mise à jour d'un membre (backlog MEM-06, MEM-07,
 * MEM-08, MEM-09).
 *
 * <p>Le nom est saisi en deux champs {@code lastName} / {@code firstName},
 * recomposés en {@code name} par le service. {@code name} reste accepté en
 * entrée pour rétrocompatibilité : fourni sans {@code lastName}, il alimente
 * {@code lastName}. Le service exige au moins l'un des deux.</p>
 *
 * <p>{@code civilStatus} est <strong>legacy</strong> : il mélangeait genre et
 * nature juridique. Un client qui n'envoie que lui reste servi (le service en
 * dérive {@code gender} et {@code personType}), mais tout nouveau client
 * renseigne les champs dédiés.</p>
 */
public record MemberUpsertDto(
        // Code interne producteur ; généré MB-YYYY-NNNN si vide (création uniquement).
        @Size(max = 40) String code,

        // Rétrocompat : nom complet legacy. Préférer lastName / firstName.
        @Size(max = 120) String name,
        @Size(max = 120) String lastName,
        @Size(max = 120) String firstName,

        // Legacy, facultatif : dérivé de gender / personType s'il est absent.
        MemberCivilStatus civilStatus,

        MemberGender gender,
        MemberPersonType personType,
        MemberMaritalStatus maritalStatus,
        @Size(max = 120) String birthPlace,
        @Valid MemberLegalIdentityDto legalIdentity,

        LocalDate birthDate,
        Integer birthYear,

        // Legacy : première pièce d'identité. Préférer identityDocuments.
        @Size(max = 120) String idDocType,
        @Size(max = 80) String idDocNumber,
        UUID idCardFileId,
        List<@Valid MemberIdentityDocumentDto> identityDocuments,

        @Valid MemberHouseholdDto household,
        @Valid MemberEnrolmentDto enrolment,

        UUID sectionId,
        UUID followUpAgentMemberId,

        /** Le producteur est aussi délégué collecteur. */
        Boolean collector,
        /** Rémunération propre au délégué. Vide : taux commun du tenant. */
        @jakarta.validation.constraints.DecimalMin(value = "0", message = "Taux négatif interdit")
        java.math.BigDecimal collectorMarginRate,
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
        @Size(max = 120) String mobileMoneyHolderName,
        Boolean mobileMoneyMandateOnFile,
        @Size(max = 500) String notes
) {}
