package com.ntech.cabosse.importjournal.service;

import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * La ligne d'audit d'un import appliqué (SEC-16).
 *
 * <p>Le journal d'import ({@link ImportJournal}) dit le détail : refus,
 * décisions, fiches créées. L'audit, lui, dit qu'un import a eu lieu, par
 * qui et avec quelle ampleur, au même endroit que toutes les autres
 * écritures de la structure. Un seul point d'écriture pour que les dix-huit
 * imports racontent la même chose de la même façon.</p>
 */
@ApplicationScoped
public class ImportAudit {

    @Inject AuditService audit;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    /**
     * Trace un import appliqué.
     *
     * @param domain      code stable du domaine ({@code parcels}, {@code members}…)
     * @param domainLabel le domaine tel qu'on le lit (« parcelles »)
     * @param fileName    nom du fichier déposé, ou {@code null} si l'écran ne le transmet pas
     * @param importRunId identifiant de la trace du journal d'import, ou {@code null}
     */
    public void committed(String domain, String domainLabel, String fileName,
                          int created, int updated, int rejected, UUID importRunId) {
        StringBuilder description = new StringBuilder("Import ").append(domainLabel)
                .append(" : ").append(created).append(created > 1 ? " lignes créées" : " ligne créée");
        if (updated > 0) {
            description.append(", ").append(updated)
                    .append(updated > 1 ? " mises à jour" : " mise à jour");
        }
        description.append(", ").append(rejected).append(rejected > 1 ? " rejetées" : " rejetée");
        if (fileName != null && !fileName.isBlank()) {
            description.append(", fichier ").append(fileName);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("domain", domain);
        if (fileName != null && !fileName.isBlank()) payload.put("fileName", fileName);
        payload.put("created", created);
        payload.put("updated", updated);
        payload.put("rejected", rejected);
        if (importRunId != null) payload.put("importRunId", importRunId.toString());

        audit.event(AuditEventType.DATA_IMPORTED)
                .actorEmail(actor())
                .target("import", importRunId != null ? importRunId.toString() : domain, domainLabel)
                .tenant(tenantContext.tenantId(), null)
                .description(description.toString())
                .payload(payload)
                .record();
    }

    /**
     * Trace l'import d'une pièce unique (bon de commande, vente, ordre de
     * fabrication) : ces imports écrivent une pièce par appel, et c'est
     * la pièce qu'on veut retrouver depuis le journal.
     */
    public void committedOne(String domain, String domainLabel, UUID targetId, String ref) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("domain", domain);
        payload.put("created", 1);
        payload.put("updated", 0);
        payload.put("rejected", 0);
        if (ref != null) payload.put("ref", ref);

        audit.event(AuditEventType.DATA_IMPORTED)
                .actorEmail(actor())
                .target(domain, targetId != null ? targetId.toString() : null, ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Import " + domainLabel + " : 1 ligne créée, 0 rejetée"
                        + (ref != null ? " (" + ref + ")" : ""))
                .payload(payload)
                .record();
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }
}
