package com.ntech.cabosse.crm.dto;

import com.ntech.cabosse.crm.entity.InteractionKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Un échange à consigner. En même temps, on peut poser la prochaine
 * action : c'est le geste naturel au sortir d'un appel.
 */
public record InteractionCreateDto(
        @NotNull InteractionKind kind,
        @NotNull LocalDate date,
        @NotBlank @Size(max = 4000) String summary,
        LocalDate nextActionAt,
        @Size(max = 200) String nextActionLabel
) {}
