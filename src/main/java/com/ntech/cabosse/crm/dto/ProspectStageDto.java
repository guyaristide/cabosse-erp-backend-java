package com.ntech.cabosse.crm.dto;

import com.ntech.cabosse.crm.entity.ProspectStage;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Faire passer un prospect à une autre étape ; perdu, avec son motif. */
public record ProspectStageDto(
        @NotNull ProspectStage stage,
        @Size(max = 400) String lostReason
) {}
