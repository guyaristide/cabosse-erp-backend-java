package com.ntech.cabosse.eudr.entity;

/**
 * Catégorie de pièce justificative attendue dans un dossier EUDR.
 *
 * <p>Référence : EUDR Articles 3 (origine légale), 9 (diligence
 * raisonnée), 10 (évaluation du risque). Les pièces minimales pour un
 * dossier {@link EudrStatus#COMPLIANT} sont :
 * {@link #LAND_TITLE} ou {@link #MAYOR_ATTESTATION}, et {@link #GPS_REPORT}
 * (polygone précis) ou {@link #SATELLITE_ANALYSIS} pour les parcelles
 * &lt; 4 ha.</p>
 */
public enum EudrDocumentType {
    /** Titre foncier ou certificat foncier rural (CFR). */
    LAND_TITLE,
    /** Attestation administrative locale (mairie, sous-préfecture). */
    MAYOR_ATTESTATION,
    /** Rapport de géolocalisation GPS avec métadonnées de précision. */
    GPS_REPORT,
    /** Analyse satellite de la couverture forestière (GFW, Copernicus, prestataire SIG). */
    SATELLITE_ANALYSIS,
    /** Consentement communautaire (CLIP) pour parcelles proches de terres communautaires. */
    COMMUNITY_CONSENT,
    /** Audit interne EUDR annuel (Art. 10). */
    INTERNAL_AUDIT,
    /** Autre document support (photos, attestations diverses). */
    OTHER
}
