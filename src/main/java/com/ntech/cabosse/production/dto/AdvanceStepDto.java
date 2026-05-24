package com.ntech.cabosse.production.dto;

import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Passage à l'étape suivante avec note optionnelle")
public record AdvanceStepDto(
        @Size(max = 500) String notes
) {}
