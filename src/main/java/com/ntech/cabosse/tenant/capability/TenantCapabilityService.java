package com.ntech.cabosse.tenant.capability;

import com.ntech.cabosse.catalog.entity.IndustryEntity;
import com.ntech.cabosse.catalog.repository.IndustryRepository;
import com.ntech.cabosse.tenant.entity.TenantActivity;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dérive les capacités fonctionnelles d'un tenant à partir de deux
 * dimensions orthogonales (cf. NEIBA-TECH-2026-003 §2) :
 *
 * <ol>
 *   <li>{@link TenantEntity#organizationModel} — structure juridique.
 *       {@code COOPERATIVE} et {@code INFORMAL_GROUP} activent
 *       automatiquement {@link TenantCapability#HAS_MEMBERS} et
 *       {@link TenantCapability#HAS_SUSTAINABILITY} (sustainability
 *       reste activée même sur structures privées si certifications
 *       déclarées).</li>
 *   <li>{@link TenantEntity#activities} — filières économiques. Chaque
 *       {@link IndustryEntity} active un ensemble de capacités déclaré
 *       dans son champ {@code activates}.</li>
 * </ol>
 *
 * <p>Ce service est l'unique point de vérité pour les feature flags
 * fonctionnels du tenant (côté backend comme côté frontend via le DTO
 * exposé par {@code /api/v1/me}). Aucun module métier ne doit dériver
 * ses propres flags ailleurs.</p>
 */
@ApplicationScoped
public class TenantCapabilityService {

    @Inject TenantRepository tenants;
    @Inject IndustryRepository industries;

    /** Capacités d'un tenant par son UUID. */
    public Set<TenantCapability> capabilitiesOf(UUID tenantId) {
        TenantEntity t = tenants.findById(tenantId);
        return capabilitiesOf(t);
    }

    /**
     * Capacités d'un tenant donné. Acceptant l'entité directement permet
     * d'éviter une seconde lecture quand l'appelant l'a déjà en main.
     */
    public Set<TenantCapability> capabilitiesOf(TenantEntity tenant) {
        if (tenant == null) return EnumSet.noneOf(TenantCapability.class);
        List<String> industryCodes = new ArrayList<>();
        if (tenant.activities != null) {
            for (TenantActivity a : tenant.activities) {
                if (a != null && a.code != null) industryCodes.add(a.code);
            }
        }
        Map<TenantCapability, List<CapabilitySource>> preview = previewCapabilities(
                tenant.organizationModel, industryCodes, tenant.certifications);
        if (preview.isEmpty()) return EnumSet.noneOf(TenantCapability.class);
        return EnumSet.copyOf(preview.keySet());
    }

    /** Test rapide d'une capacité unique. */
    public boolean has(TenantEntity tenant, TenantCapability cap) {
        return capabilitiesOf(tenant).contains(cap);
    }

    public boolean has(UUID tenantId, TenantCapability cap) {
        return capabilitiesOf(tenantId).contains(cap);
    }

    /**
     * Preview à blanc : calcule les capacités effectives pour une combinaison
     * d'inputs non-persistée. Utilisé par l'UI backoffice (provisioning,
     * édition tenant) pour montrer quelles capacités vont s'activer avant
     * validation du formulaire.
     *
     * <p>Pour chaque capacité activée, l'appelant reçoit la liste de ses
     * <em>sources</em> (organisation, filière, certification). Une capacité
     * peut avoir plusieurs sources (ex : HAS_SUSTAINABILITY active par
     * organisation COOPERATIVE et par certification Fairtrade).</p>
     *
     * @param organizationModel structure juridique, défaut PRIVATE_COMPANY si null
     * @param industryCodes     codes des activités déclarées, peut être null
     * @param certifications    codes des certifications déclarées, peut être null
     */
    public Map<TenantCapability, List<CapabilitySource>> previewCapabilities(
            TenantOrganizationModel organizationModel,
            Collection<String> industryCodes,
            Collection<String> certifications) {

        Map<TenantCapability, List<CapabilitySource>> result = new EnumMap<>(TenantCapability.class);

        TenantOrganizationModel org = organizationModel != null
                ? organizationModel
                : TenantOrganizationModel.PRIVATE_COMPANY;

        // ─── Dimension 1 : structure organisationnelle ───
        if (org == TenantOrganizationModel.COOPERATIVE
                || org == TenantOrganizationModel.INFORMAL_GROUP) {
            addSource(result, TenantCapability.HAS_MEMBERS,
                    CapabilitySource.organization(org));
            addSource(result, TenantCapability.HAS_SUSTAINABILITY,
                    CapabilitySource.organization(org));
        }

        // ─── Élargissement HAS_SUSTAINABILITY par certifications ───
        if (certifications != null) {
            for (String cert : certifications) {
                if (cert == null || cert.isBlank()) continue;
                addSource(result, TenantCapability.HAS_SUSTAINABILITY,
                        CapabilitySource.certification(cert));
            }
        }

        // ─── Dimension 2 : filières / activités ───
        if (industryCodes != null) {
            for (String code : industryCodes) {
                if (code == null || code.isBlank()) continue;
                IndustryEntity ind = industries.findById(code);
                if (ind == null || ind.activates == null) continue;
                for (String capName : ind.activates) {
                    try {
                        TenantCapability cap = TenantCapability.valueOf(capName);
                        addSource(result, cap, CapabilitySource.industry(code, ind.label));
                    } catch (IllegalArgumentException ignored) {
                        // Code invalide dans le seed — ignoré (couvert par test).
                    }
                }
            }
        }

        return result;
    }

    private static void addSource(
            Map<TenantCapability, List<CapabilitySource>> map,
            TenantCapability cap,
            CapabilitySource source) {
        map.computeIfAbsent(cap, k -> new ArrayList<>()).add(source);
    }

    /**
     * Origine d'activation d'une capacité — utilisée par la preview UI pour
     * expliquer pourquoi telle ou telle capacité s'est activée.
     */
    public record CapabilitySource(SourceType type, String code, String label) {

        public enum SourceType { ORGANIZATION, INDUSTRY, CERTIFICATION }

        public static CapabilitySource organization(TenantOrganizationModel org) {
            return new CapabilitySource(SourceType.ORGANIZATION, org.name(), null);
        }

        public static CapabilitySource industry(String code, String label) {
            return new CapabilitySource(SourceType.INDUSTRY, code, label);
        }

        public static CapabilitySource certification(String code) {
            return new CapabilitySource(SourceType.CERTIFICATION, code, null);
        }
    }
}
