package com.ntech.cabosse.tenant.dto;

import com.ntech.cabosse.tenant.dto.CreateTenantPayloadDto.ActivityPayload;
import com.ntech.cabosse.tenant.dto.CreateTenantPayloadDto.AddressPayload;
import com.ntech.cabosse.tenant.dto.CreateTenantPayloadDto.BillingPayload;
import com.ntech.cabosse.tenant.dto.CreateTenantPayloadDto.ContactPayload;
import com.ntech.cabosse.tenant.dto.CreateTenantPayloadDto.LegalPayload;
import com.ntech.cabosse.tenant.dto.CreateTenantPayloadDto.PreferencesPayload;
import com.ntech.cabosse.tenant.entity.CommercialStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Payload JSON d'édition d'un tenant existant (PUT).
 *
 * <p>Champs <strong>non éditables</strong> via cet endpoint (volontairement) :
 * <ul>
 *   <li>{@code slug} : immuable une fois le tenant créé (référencé dans
 *       l'URL, l'audit, les liens externes).</li>
 *   <li>{@code admin} : géré par d'autres endpoints (invitation user,
 *       changement de rôle).</li>
 *   <li>{@code logo} : géré par endpoints dédiés
 *       ({@code PUT/DELETE /tenants/{id}/logo}).</li>
 * </ul>
 *
 * <p>Tous les autres champs sont éditables d'un coup, dans une seule
 * transaction. Le {@code commercialStatus} en particulier peut changer
 * (TRIAL → PILOT → PRODUCTION) ; chaque transition est journalisée par
 * le service.</p>
 */
@Schema(description = "Payload de mise à jour d'un tenant existant")
public record UpdateTenantPayloadDto(

        @NotBlank @Size(min = 3, max = 120) String name,

        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Couleur hexa attendue (#RRGGBB)")
        String brandColor,

        @Valid @NotNull LegalPayload legal,
        @Valid @NotNull AddressPayload address,
        @Valid @NotNull ContactPayload contact,
        @Valid @NotNull BillingPayload billing,
        @Valid @NotNull PreferencesPayload preferences,

        @NotEmpty
        List<@Valid ActivityPayload> activities,

        List<String> certifications,

        @Min(1) @Max(50) int initialSitesCount,

        @NotBlank String planCode,
        @NotNull CommercialStatus commercialStatus,
        @Min(1) @Max(90) Integer trialDurationDays

) {}
