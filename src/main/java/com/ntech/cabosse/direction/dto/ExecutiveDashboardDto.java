package com.ntech.cabosse.direction.dto;

import java.util.List;

public record ExecutiveDashboardDto(
        String period,
        List<ExecutiveKpiDto> kpis,
        List<ExecutiveAlertDto> alerts
) {}
