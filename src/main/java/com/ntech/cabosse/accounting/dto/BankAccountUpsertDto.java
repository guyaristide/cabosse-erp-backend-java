package com.ntech.cabosse.accounting.dto;

import com.ntech.cabosse.accounting.entity.BankAccountKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Payload de création / modification d'un compte bancaire. */
public record BankAccountUpsertDto(
        @NotBlank @Size(max = 80) String bankName,
        @Size(max = 60) String accountNumber,
        @NotBlank @Size(max = 10) String syscohadaAccount,
        @NotBlank @Size(max = 80) String label,
        @Size(max = 120) String sub,
        @NotNull BankAccountKind kind,
        Boolean active
) {}
