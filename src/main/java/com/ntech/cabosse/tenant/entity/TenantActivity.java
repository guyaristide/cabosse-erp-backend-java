package com.ntech.cabosse.tenant.entity;

/**
 * Activité économique déclarée par le tenant. Sub-document de
 * {@link TenantEntity}, embarqué dans la liste {@code activities}.
 *
 * <p>Un tenant a 1..N activités. Une et une seule est marquée
 * {@code isPrimary} ; elle sert de fallback quand une opération métier
 * (BC, OF, vente) n'arrive pas à dériver son activité depuis l'article
 * concerné.</p>
 *
 * <p>Le code est libre (slug) et non contraint à une liste fermée — la
 * plateforme est volontairement filière-agnostique (cf. règle projet
 * "Pas de marque dans le modèle de domaine" et "filière-agnostique").</p>
 */
public class TenantActivity {

    public String code;
    public String label;
    public boolean isPrimary;

    public TenantActivity() {}

    public TenantActivity(String code, String label, boolean isPrimary) {
        this.code = code;
        this.label = label;
        this.isPrimary = isPrimary;
    }
}
