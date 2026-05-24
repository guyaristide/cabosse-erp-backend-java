package com.ntech.cabosse.settings.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Payload d'écriture SMTP.
 *
 * <p>{@code password} suit la convention "patch sensible" :
 * <ul>
 *   <li>{@code null} ou vide → conserve la valeur en BD,</li>
 *   <li>{@code "<<clear>>"} → efface la valeur,</li>
 *   <li>autre → remplace.</li>
 * </ul>
 * Comme l'UI affiche un placeholder masqué, l'admin ne tape un nouveau
 * mot de passe que quand il veut explicitement le changer.</p>
 */
@Schema(description = "Payload d'écriture SMTP")
public record EmailSettingsUpsertDto(

        @NotBlank(message = "Adresse expéditeur requise")
        @Email(message = "Adresse expéditeur invalide")
        String from,

        @NotBlank(message = "Serveur SMTP requis")
        String host,

        @Min(value = 1, message = "Port invalide")
        @Max(value = 65535, message = "Port invalide")
        int port,

        @NotBlank(message = "Identifiant SMTP requis")
        String username,

        /** Voir convention « patch sensible » dans la doc de la classe. */
        String password,

        @Pattern(regexp = "NONE|REQUIRED|OPTIONAL",
                message = "Valeur autorisée : NONE | REQUIRED | OPTIONAL")
        String startTls,

        boolean mockMode

) {}
