package com.ntech.cabosse.catalog.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Plan tarifaire : vue admin (avec activation)")
public record PlanAdminDto(
        String code,
        String name,
        String description,
        BigDecimal monthlyPriceFcfa,
        BigDecimal yearlyPriceFcfa,
        int maxUsers,
        int maxMembers,
        int maxSites,
        List<String> includedModules,
        List<String> features,
        boolean active
) {}
