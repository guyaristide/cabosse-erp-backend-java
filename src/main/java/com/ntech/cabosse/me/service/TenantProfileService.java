package com.ntech.cabosse.me.service;

import com.ntech.cabosse.me.dto.TenantAgrementDto;
import com.ntech.cabosse.me.dto.TenantProfileContactDto;
import com.ntech.cabosse.me.dto.TenantProfileDto;
import com.ntech.cabosse.me.dto.UpdateTenantProfilePayloadDto;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantAddress;
import com.ntech.cabosse.tenant.entity.TenantContact;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantLegal;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Profil coopérative du tenant courant (backlog COOP-03/04/05).
 *
 * <p>Endpoint dédié à l'administration tenant ({@code /me/tenant/profile}),
 * distinct du back-office plateforme
 * ({@link com.ntech.cabosse.tenant.service.TenantUpdateService}). La coop
 * y renseigne ses paramètres de base : identité légale (sigle, constitution,
 * agréments), adresse (région/département tenant), contacts et liste des
 * produits vendus. Remplacement complet des sections à chaque écriture.</p>
 */
@ApplicationScoped
public class TenantProfileService {

    @Inject TenantContext tenantContext;
    @Inject TenantRepository tenants;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    public TenantProfileDto get() {
        return toDto(loadCurrentTenant());
    }

    @Transactional
    public TenantProfileDto update(UpdateTenantProfilePayloadDto p) {
        TenantEntity t = loadCurrentTenant();

        // ─── Identité (nom d'usage) ───
        t.name = p.name().trim();

        // ─── Légal ───
        TenantLegal legal = t.legal != null ? t.legal : new TenantLegal();
        legal.legalName = trimOrNull(p.legalName());
        legal.legalForm = p.legalForm();
        legal.sigle = trimOrNull(p.sigle());
        legal.rccm = trimOrNull(p.rccm());
        legal.taxId = trimOrNull(p.taxId());
        legal.vatNumber = trimOrNull(p.vatNumber());
        legal.constitutedAt = p.constitutedAt();
        legal.agrements = new ArrayList<>();
        if (p.agrements() != null) {
            p.agrements().stream()
                    .filter(a -> a != null && a.type() != null && !a.type().isBlank())
                    .map(TenantAgrementDto::toEntity)
                    .forEach(legal.agrements::add);
        }
        t.legal = legal;

        // ─── Adresse (cityId préservé) ───
        TenantAddress address = t.address != null ? t.address : new TenantAddress();
        address.street = trimOrNull(p.street());
        address.postalCode = trimOrNull(p.postalCode());
        address.city = trimOrNull(p.city());
        address.country = p.country() != null && !p.country().isBlank()
                ? p.country().trim().toUpperCase(Locale.ROOT) : null;
        address.regionCode = trimOrNull(p.regionCode());
        address.departmentCode = trimOrNull(p.departmentCode());
        t.address = address;

        // ─── Contacts (un seul principal) ───
        t.contacts = buildContacts(p.contacts());
        // Miroir sur le contact mono legacy = le principal, pour compat.
        t.contact = t.contacts.stream().filter(c -> c.isPrimary).findFirst()
                .orElse(t.contacts.isEmpty() ? t.contact : t.contacts.get(0));

        t.updatedAt = Instant.now();
        t.updatedBy = safeUserId();
        tenants.update(t);

        audit.event(AuditEventType.TENANT_UPDATED)
                .actorEmail(actor())
                .target("tenant", t.id.toString(), t.name)
                .tenant(t.id, null)
                .description("Mise à jour du profil coopérative")
                .record();

        return toDto(t);
    }

    private List<TenantContact> buildContacts(List<TenantProfileContactDto> in) {
        List<TenantContact> out = new ArrayList<>();
        if (in == null) return out;
        in.stream()
                .filter(c -> c != null && c.name() != null && !c.name().isBlank())
                .map(TenantProfileContactDto::toEntity)
                .forEach(out::add);
        if (out.isEmpty()) return out;
        long primaries = out.stream().filter(c -> c.isPrimary).count();
        if (primaries > 1) {
            throw new BusinessException(
                    Messages.msg("m.me-single-primary-contact", String.valueOf(primaries)));
        }
        if (primaries == 0) {
            out.get(0).isPrimary = true;
        }
        return out;
    }

    private TenantProfileDto toDto(TenantEntity t) {
        TenantLegal l = t.legal != null ? t.legal : new TenantLegal();
        TenantAddress a = t.address != null ? t.address : new TenantAddress();

        List<TenantAgrementDto> agrements = (l.agrements != null ? l.agrements : List.<com.ntech.cabosse.tenant.entity.TenantAgrement>of())
                .stream().map(TenantAgrementDto::from).toList();

        List<TenantProfileContactDto> contacts;
        if (t.contacts != null && !t.contacts.isEmpty()) {
            contacts = t.contacts.stream().map(TenantProfileContactDto::from).toList();
        } else if (t.contact != null && t.contact.name != null) {
            // Bascule du contact mono legacy vers la liste (lecture seule).
            TenantContact c = t.contact;
            c.isPrimary = true;
            contacts = List.of(TenantProfileContactDto.from(c));
        } else {
            contacts = List.of();
        }

        return new TenantProfileDto(
                t.id, t.name,
                l.legalName, l.legalForm, l.sigle, l.rccm, l.taxId, l.vatNumber,
                l.constitutedAt, agrements,
                a.street, a.postalCode, a.city, a.country, a.regionCode, a.departmentCode,
                contacts
        );
    }

    private static String slugCode(String label) {
        String n = Normalizer.normalize(label, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 24) n = n.substring(0, 24);
        return n.isEmpty() ? "PRODUIT" : n;
    }

    private static String trimOrNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private TenantEntity loadCurrentTenant() {
        TenantEntity t = tenants.findById(tenantContext.tenantId());
        if (t == null) {
            throw new NotFoundException(Messages.msg("m.me-current-tenant-not-found"));
        }
        return t;
    }

    private java.util.UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
