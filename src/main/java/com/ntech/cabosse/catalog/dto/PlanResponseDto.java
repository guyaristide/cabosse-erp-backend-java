package com.ntech.cabosse.catalog.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Plan tarifaire actif de la plateforme")
public record PlanResponseDto(
        String code,
        String name,
        String description,
        BigDecimal monthlyPriceFcfa,
        BigDecimal yearlyPriceFcfa,
        int maxUsers,
        int maxSites,
        List<String> includedModules,
        List<String> features
) {}
