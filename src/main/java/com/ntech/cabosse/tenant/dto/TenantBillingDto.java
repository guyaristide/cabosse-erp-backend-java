package com.ntech.cabosse.tenant.dto;

import com.ntech.cabosse.tenant.entity.BillingCycle;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Coordonnées de facturation du tenant")
public record TenantBillingDto(

        String email,
        BillingCycle cycle

) {}
