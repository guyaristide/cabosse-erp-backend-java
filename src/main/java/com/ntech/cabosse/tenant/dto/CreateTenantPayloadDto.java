package com.ntech.cabosse.tenant.dto;

import com.ntech.cabosse.tenant.entity.BillingCycle;
import com.ntech.cabosse.tenant.entity.CommercialStatus;
import com.ntech.cabosse.tenant.entity.LegalForm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Payload JSON envoyé par le frontend lors d'un provisioning tenant.
 *
 * <p>Multipart : ce DTO est la partie {@code payload} (sérialisée en JSON),
 * accompagnée optionnellement d'une partie {@code logo} (fichier binaire).
 * Cf. {@code POST /api/v1/admin/tenants}.</p>
 *
 * <p>Bean Validation : toute contrainte appliquée ici est vérifiée
 * automatiquement par RESTEasy à la désérialisation. Pas de validation
 * manuelle dans le service pour ce que ces annotations couvrent
 * (cf. CLAUDE.md §8.1).</p>
 */
@Schema(description = "Payload de provisioning d'un nouveau tenant")
public record CreateTenantPayloadDto(

        // ─── Identité ───
        @NotBlank(message = "Nom requis")
        @Size(min = 3, max = 120, message = "Nom entre 3 et 120 caractères")
        String name,

        @NotBlank(message = "Slug requis")
        @Pattern(regexp = "^[a-z0-9-]{3,50}$",
                message = "Slug en minuscules, chiffres et tirets, 3 à 50 caractères")
        String slug,

        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Couleur hexa attendue (#RRGGBB)")
        @Schema(example = "#1A1A1A")
        String brandColor,

        // ─── Légal ───
        @Valid @NotNull(message = "Informations légales requises") LegalPayload legal,

        // ─── Adresse ───
        @Valid @NotNull(message = "Adresse requise") AddressPayload address,

        // ─── Contact principal ───
        @Valid @NotNull(message = "Contact requis") ContactPayload contact,

        // ─── Facturation ───
        @Valid @NotNull(message = "Coordonnées de facturation requises") BillingPayload billing,

        // ─── Préférences ───
        @Valid @NotNull(message = "Préférences requises") PreferencesPayload preferences,

        // ─── Activités économiques ───
        @NotEmpty(message = "Au moins une activité requise")
        List<@Valid ActivityPayload> activities,

        // ─── Certifications ───
        List<String> certifications,

        // ─── Sites initiaux ───
        @Min(value = 1, message = "Au moins un site")
        @Max(value = 50, message = "50 sites maximum")
        int initialSitesCount,

        // ─── Plan & souscription ───
        @NotBlank(message = "Plan requis") String planCode,
        @NotNull(message = "Statut commercial requis") CommercialStatus commercialStatus,
        @Min(1) @Max(90) Integer trialDurationDays,

        // ─── Admin du tenant ───
        @Valid @NotNull(message = "Admin du tenant requis") AdminPayload admin

) {

    @Schema(description = "Sous-payload légal")
    public record LegalPayload(
            @NotBlank @Size(min = 3, max = 200) String legalName,
            @NotNull LegalForm legalForm,
            @NotBlank @Size(min = 5, max = 40) String rccm,
            @NotBlank @Size(min = 5, max = 40) String taxId,
            @Size(max = 40) String vatNumber
    ) {}

    @Schema(description = "Sous-payload adresse")
    public record AddressPayload(
            @NotBlank @Size(min = 3, max = 200) String street,
            @Size(max = 20) String postalCode,
            @NotBlank @Size(min = 2, max = 80) String city,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "ISO 3166-1 alpha-2 attendu") String country,
            @Schema(description = "Code région du catalogue, optionnel — validé si fourni", example = "ABJ")
            String regionCode,
            @Schema(description = "Identifiant ville du catalogue, optionnel — validé si fourni")
            UUID cityId
    ) {}

    @Schema(description = "Sous-payload contact principal")
    public record ContactPayload(
            @NotBlank @Size(min = 3, max = 120) String name,
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "^\\+?[\\d\\s()-]{6,25}$", message = "Numéro de téléphone invalide") String phone
    ) {}

    @Schema(description = "Sous-payload facturation")
    public record BillingPayload(
            @NotBlank @Email String email,
            @NotNull BillingCycle cycle
    ) {}

    @Schema(description = "Sous-payload préférences opérationnelles")
    public record PreferencesPayload(
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "ISO 4217 attendu") String currency,
            @NotBlank @Pattern(regexp = "^[a-z]{2}$", message = "ISO 639-1 attendu") String language,
            @NotBlank String timezone,
            @Min(1) @Max(50) int initialSitesCount,
            @Schema(description = "Si vrai (défaut), l'entreprise récupère la TVA sur les achats ; "
                    + "le CMUP des matières utilise le PU HT. Si faux, la TVA devient une charge "
                    + "incorporée au coût d'acquisition.",
                    defaultValue = "true")
            Boolean vatRecoverable
    ) {}

    @Schema(description = "Sous-payload activité économique")
    public record ActivityPayload(
            @NotBlank @Size(min = 2, max = 40) String code,
            @NotBlank @Size(min = 2, max = 80) String label,
            boolean isPrimary
    ) {}

    @Schema(description = "Sous-payload admin du tenant — destinataire de l'invitation")
    public record AdminPayload(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 2, max = 80) String firstName,
            @NotBlank @Size(min = 2, max = 80) String lastName
    ) {}
}
