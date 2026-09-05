package com.ntech.cabosse.campaign.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Un changement de barème sur une campagne.
 *
 * <p>Le barème entier est conservé de part et d'autre, pas seulement le
 * prix de base : une prime qualité déplace autant d'argent vers le
 * producteur qu'un centime de prix bord champ, et ne garder que le prix
 * de base aurait laissé une voie discrète pour changer ce qui est payé.</p>
 *
 * <p>Le motif est obligatoire. Sans lui, l'historique dirait qu'un prix a
 * changé sans dire pourquoi, ce qui ne se conteste pas davantage qu'une
 * absence de trace.</p>
 */
public class TariffChange {

    public BigDecimal previousBasePricePerKg;
    public BigDecimal newBasePricePerKg;

    public BigDecimal previousRistournePct;
    public BigDecimal newRistournePct;

    public List<QualityPremium> previousQualityPremiums = new ArrayList<>();
    public List<QualityPremium> newQualityPremiums = new ArrayList<>();

    /** Pourquoi le barème a changé. Exigé à la saisie. */
    public String reason;

    public Instant changedAt;
    public UUID changedBy;
    public String changedByEmail;

    public TariffChange() {}
}
