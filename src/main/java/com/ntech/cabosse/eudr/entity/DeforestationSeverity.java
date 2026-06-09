package com.ntech.cabosse.eudr.entity;

/** Gravité d'une alerte de déforestation détectée par analyse satellite. */
public enum DeforestationSeverity {
    /** Perte arborée &lt; 5% de la parcelle. */
    LOW,
    /** Perte arborée 5–30%. */
    MEDIUM,
    /** Perte arborée &gt; 30%, ou perte massive détectée par RADD Alert. */
    HIGH
}
