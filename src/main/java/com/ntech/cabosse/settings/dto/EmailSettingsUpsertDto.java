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

        @NotBlank(message = "{v.adresse-expediteur-requise}")
        @Email(message = "{v.adresse-expediteur-invalide}")
        String from,

        @NotBlank(message = "{v.serveur-smtp-requis}")
        String host,

        @Min(value = 1, message = "{v.port-invalide}")
        @Max(value = 65535, message = "{v.port-invalide}")
        int port,

        @NotBlank(message = "{v.identifiant-smtp-requis}")
        String username,

        /** Voir convention « patch sensible » dans la doc de la classe. */
        String password,

        /**
         * Boîte qui reçoit les avis d'assistance. Vide → l'adresse
         * d'expédition fait office : c'est déjà la boîte de l'éditeur, et
         * un avis qui revient à l'expéditeur vaut mieux qu'aucun avis.
         */
        @Email(message = "{v.adresse-e-mail-invalide}")
        String supportInbox,

        @Pattern(regexp = "NONE|REQUIRED|OPTIONAL",
                message = "{v.valeur-autorisee-none-required-optional}")
        String startTls,

        boolean mockMode

) {}
