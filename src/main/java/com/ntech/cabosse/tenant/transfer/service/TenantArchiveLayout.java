package com.ntech.cabosse.tenant.transfer.service;

import java.util.List;

/**
 * La forme de l'archive, partagée par l'export et la restauration.
 *
 * <p>Écrite une fois : deux constantes qui divergent, et l'archive
 * produite ne se relit plus.</p>
 */
public final class TenantArchiveLayout {

    private TenantArchiveLayout() {}

    /** Version du format. À incrémenter dès que la disposition change. */
    public static final int FORMAT = 1;

    public static final String MANIFEST = "manifest.json";
    public static final String TENANT_DIR = "tenant/";
    public static final String CONTROL_DIR = "control/";
    public static final String FILES_DIR = "files/";

    /**
     * Collections du plan de contrôle emportées, avec le champ qui les
     * rattache au tenant.
     *
     * <p>Les comptes emportés sont ceux qui entrent dans la structure, leur
     * structure d'origine fût-elle une autre (SAAS-29) ; à la
     * restauration, un compte déjà présent sur le serveur est reconnu
     * comme la même personne et laissé tel quel, seule son appartenance
     * est posée.</p>
     *
     * <p>Les jetons de rafraîchissement n'y sont pas, volontairement :
     * les restaurer ressusciterait des sessions ouvertes sur un autre
     * serveur. La trace d'audit y est, elle : une sauvegarde qui perd
     * l'historique des décisions ne sert pas à vérifier grand-chose.</p>
     */
    public static final List<Slice> CONTROL_SLICES = List.of(
            new Slice("tenants", "_id"),
            new Slice("users", "tenantId"),
            new Slice("memberships", "tenantId"),
            new Slice("subscriptions", "tenantId"),
            new Slice("support_tickets", "tenantId"),
            new Slice("cloud_files", "tenantId"),
            new Slice("global_audit", "tenantId"),
            new Slice("notification_providers", "tenantId"),
            // Le fil commercial d'une structure la suit (SAAS-31) ; les
            // prospects, eux, n'appartiennent à aucune structure.
            new Slice("crm_interactions", "tenantId"),
            new Slice("crm_tenant_followups", "tenantId"));

    /** Ce qu'on laisse volontairement de côté, dit à l'utilisateur. */
    public static final List<String> EXCLUDED = List.of(
            "refresh_tokens : les restaurer ressusciterait des sessions ouvertes ailleurs",
            "tenant_backups : le journal des sauvegardes appartient au serveur, pas au tenant");

    /** Une collection du plan de contrôle et son champ de rattachement. */
    public record Slice(String collection, String tenantField) {}
}
