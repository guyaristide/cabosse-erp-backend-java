package com.ntech.cabosse.intake.entity;

import java.math.BigDecimal;

/**
 * Une ligne du fichier de traçabilité qu'une comptabilisation n'a pas pu
 * transformer en reçu.
 *
 * <p>Écrit le 12/09/2026. Jusque-là une ligne refusée ne vivait que le
 * temps d'un message à l'écran : le bordereau s'affichait « comptabilisé »
 * et plus rien ne disait qu'il lui manquait de la matière. Il a fallu une
 * journée pour retrouver qu'un numéro de reçu officiel était employé deux
 * fois par le système national, et que la garde anti-doublon avait fait
 * son travail en silence.</p>
 */
public class IntakeSkippedRow {

    /** Ligne du fichier, en-tête comprise, comme le tableur la numérote. */
    public Integer rowNumber;
    /** Numéro du reçu officiel porté par la ligne. */
    public String reference;
    public String producerName;
    public BigDecimal weightKg;
    public BigDecimal amount;
    /** Ce qui a empêché la création, en clair. */
    public String reason;
}
