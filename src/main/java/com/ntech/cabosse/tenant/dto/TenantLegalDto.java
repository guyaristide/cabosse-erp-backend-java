package com.ntech.cabosse.tenant.dto;

import com.ntech.cabosse.tenant.entity.LegalForm;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Informations légales du tenant")
public record TenantLegalDto(

        String legalName,
        LegalForm legalForm,
        String rccm,
        String taxId,
        String vatNumber

) {}
