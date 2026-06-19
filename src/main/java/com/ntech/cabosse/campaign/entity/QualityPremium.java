package com.ntech.cabosse.campaign.entity;

import com.ntech.cabosse.agriculture.qc.entity.BeanGrade;

import java.math.BigDecimal;

/**
 * Prime qualité appliquée par kg pour un grade de fèves donné. Sous-doc
 * embarqué dans {@link CampaignEntity#qualityPremiums}.
 *
 * <p>Exemple coopérative cacao : {@code (GR1, 50)} = +50 FCFA/kg pour
 * les livraisons fèves classées GR1. {@code (HG, 0)} = pas de prime
 * pour hors grade (voire pénalité via valeur négative, autorisée).</p>
 */
public class QualityPremium {

    public BeanGrade grade;

    /**
     * Prime en devise tenant, par kg. Peut être négative (pénalité).
     * Additionnée au prix de base au moment du calcul de la rémunération.
     */
    public BigDecimal premiumPerKg = BigDecimal.ZERO;

    public QualityPremium() {}

    public QualityPremium(BeanGrade grade, BigDecimal premiumPerKg) {
        this.grade = grade;
        this.premiumPerKg = premiumPerKg;
    }
}
