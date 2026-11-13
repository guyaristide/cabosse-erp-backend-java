package com.ntech.cabosse.crm.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** La structure née du prospect. */
public record LinkTenantDto(@NotNull UUID tenantId) {}
