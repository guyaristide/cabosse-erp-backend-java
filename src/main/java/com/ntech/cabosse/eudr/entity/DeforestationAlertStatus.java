package com.ntech.cabosse.eudr.entity;

/** Workflow de traitement d'une alerte de déforestation. */
public enum DeforestationAlertStatus {
    /** Alerte fraîchement créée, à investiguer. */
    NEW,
    /** Direction informée, prise en compte. */
    ACKNOWLEDGED,
    /** Action corrective effectuée, alerte close. */
    RESOLVED,
    /** Faux positif (image satellite trompeuse, replantation post-coupe). */
    FALSE_POSITIVE
}
