package com.ntech.cabosse.members.service;

import com.ntech.cabosse.members.entity.MemberHousehold;
import com.ntech.cabosse.shared.exception.BusinessException;

/**
 * Cohérence du bloc ménage (backlog MEM-08).
 *
 * <p>Une enquête partielle est acceptée : on ne contrôle que les
 * combinaisons effectivement renseignées. Un total d'enfants qui ne
 * correspond pas à sa ventilation trahit une erreur de saisie, et ces
 * comptages alimentent ensuite le suivi du travail des enfants — les
 * laisser passer produirait des indicateurs faux.</p>
 */
public final class MemberHouseholdRules {

    private MemberHouseholdRules() {}

    public static void validate(MemberHousehold h) {
        if (h == null || h.isEmpty()) return;

        Integer children = h.childrenCount;

        if (children != null && h.girlsCount != null && h.boysCount != null
                && h.girlsCount + h.boysCount != children) {
            throw new BusinessException(
                    "Ménage : le nombre de filles et de garçons (" + (h.girlsCount + h.boysCount)
                            + ") doit égaler le nombre d'enfants (" + children + ").");
        }

        if (children != null && h.children0to4 != null && h.children5to17 != null
                && h.childrenOver17 != null
                && h.children0to4 + h.children5to17 + h.childrenOver17 != children) {
            throw new BusinessException(
                    "Ménage : la somme des tranches d'âge ("
                            + (h.children0to4 + h.children5to17 + h.childrenOver17)
                            + ") doit égaler le nombre d'enfants (" + children + ").");
        }

        if (children != null && h.childrenSchooled != null && h.childrenNotSchooled != null
                && h.childrenSchooled + h.childrenNotSchooled > children) {
            throw new BusinessException(
                    "Ménage : enfants scolarisés et non scolarisés ("
                            + (h.childrenSchooled + h.childrenNotSchooled)
                            + ") dépassent le nombre d'enfants (" + children + ").");
        }

        if (children != null) {
            if (h.childrenSchooled != null && h.childrenSchooled > children) {
                throw new BusinessException(
                        "Ménage : plus d'enfants scolarisés que d'enfants déclarés.");
            }
            if (h.childrenNotSchooled != null && h.childrenNotSchooled > children) {
                throw new BusinessException(
                        "Ménage : plus d'enfants non scolarisés que d'enfants déclarés.");
            }
        }
    }
}
