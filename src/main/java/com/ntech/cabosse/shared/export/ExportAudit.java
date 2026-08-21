package com.ntech.cabosse.shared.export;

import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Map;

/**
 * Helper partagé pour consigner un export de données dans le journal
 * d'audit. Factorise le boilerplate dupliqué dans chaque ressource
 * exposant un endpoint {@code /export}.
 *
 * <p>Usage typique dans une ressource :</p>
 * <pre>{@code
 *   @Inject ExportAudit exportAudit;
 *
 *   public Response export(...) {
 *       ...
 *       exportAudit.record("articles", "Matières premières", format, rows.size());
 *       return ExportResponses.build(...);
 *   }
 * }</pre>
 */
@ApplicationScoped
public class ExportAudit {

    @Inject AuditService audit;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    /**
     * Consigne un export dans le journal d'audit ({@code DATA_EXPORTED},
     * catégorie {@code OPERATIONS}).
     *
     * @param targetType type technique de la cible (ex. {@code "articles"})
     * @param title      libellé humain de l'export (ex. {@code "Matières premières"})
     * @param format     format choisi (CSV / XLSX / PDF)
     * @param rowCount   nombre de lignes exportées
     */
    public void record(String targetType, String title, ExportFormat format, int rowCount) {
        // La découverte des colonnes n'exporte aucune donnée : la
        // journaliser noierait le journal sous de faux exports.
        if (format == ExportFormat.META) return;
        String email;
        try { email = jwt.getName(); } catch (Exception e) { email = null; }
        audit.event(AuditEventType.DATA_EXPORTED)
                .actorEmail(email)
                .target(targetType, title, title)
                .tenant(tenantContext.tenantId(), null)
                .description("Export " + title + " (" + format.name() + ") · "
                        + rowCount + " ligne" + (rowCount > 1 ? "s" : ""))
                .payload(Map.of(
                        "format", format.name(),
                        "rowCount", rowCount
                ))
                .record();
    }
}
