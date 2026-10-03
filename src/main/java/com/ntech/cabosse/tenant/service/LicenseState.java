package com.ntech.cabosse.tenant.service;

/**
 * Où en est la licence d'une structure.
 *
 * <p>Quatre états plutôt que deux : entre « valable » et « échue » vivent
 * le préavis, où rien ne change encore, et le délai de grâce, où
 * l'échéance est passée mais où l'accès tient. Les confondre reviendrait à
 * couper le jour même ou à ne jamais couper.</p>
 */
public enum LicenseState {

    /** Aucune licence posée. Un tenant en essai, par exemple. */
    NONE,

    /** En cours, hors période de préavis. */
    VALID,

    /** L'échéance approche. Rien ne change encore. */
    EXPIRING,

    /** L'échéance est passée, le délai de grâce court. L'accès tient. */
    IN_GRACE,

    /** Le délai de grâce est écoulé. La structure est suspendue. */
    LAPSED
}
