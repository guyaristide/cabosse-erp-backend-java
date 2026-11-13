package com.ntech.cabosse.staff.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Un agent à inviter dans l'équipe de l'éditeur. */
public record InviteStaffDto(
        @NotBlank(message = "{v.e-mail-requis}") @Email(message = "{v.adresse-e-mail-invalide}") String email,
        @NotBlank(message = "{v.prenom-requis}") @Size(min = 2, max = 80, message = "{v.prenom-entre-2-et-80-caracteres}") String firstName,
        @NotBlank(message = "{v.nom-requis}") @Size(min = 2, max = 80, message = "{v.nom-entre-2-et-80-caracteres}") String lastName
) {}
