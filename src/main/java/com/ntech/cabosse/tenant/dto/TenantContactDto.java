package com.ntech.cabosse.tenant.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Contact principal du tenant")
public record TenantContactDto(

        String name,
        String email,
        String phone

) {}
