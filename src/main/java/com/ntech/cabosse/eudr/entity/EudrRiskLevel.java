package com.ntech.cabosse.eudr.entity;

/**
 * Niveau de risque déforestation attribué à une parcelle. Classification
 * Commission Européenne EUDR Art. 10 : faible / standard / élevé. Le
 * niveau dérive de la zone géographique (catalogue UE) et des résultats
 * d'analyse satellite.
 */
public enum EudrRiskLevel {
    LOW,
    STANDARD,
    HIGH,
    /** Non encore évalué. */
    UNKNOWN
}
