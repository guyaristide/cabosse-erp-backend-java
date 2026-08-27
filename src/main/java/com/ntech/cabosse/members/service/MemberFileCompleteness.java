package com.ntech.cabosse.members.service;

import com.ntech.cabosse.members.dto.MemberFileStatusDto;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberGender;
import com.ntech.cabosse.members.entity.MemberHousehold;
import com.ntech.cabosse.members.entity.MemberPersonType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Évalue la complétude et la fraîcheur d'un dossier producteur
 * (backlog MEM-09).
 *
 * <p>Les critères sont ceux qu'exige la fiche signalétique : identité,
 * pièce, contact, rattachement, terrain, ménage, recensement. Chaque
 * critère pèse pareil — pondérer supposerait un arbitrage métier qui n'a
 * pas été rendu, et un pourcentage grossier suffit à trier les dossiers à
 * compléter.</p>
 */
public final class MemberFileCompleteness {

    private MemberFileCompleteness() {}

    /**
     * @param m              membre à évaluer
     * @param validityMonths durée de validité d'une enquête, en mois
     * @param identityProofTypes libellés (en minuscules) des types de pièce
     *        qui établissent l'identité. Une carte de filière n'en fait pas
     *        partie : elle retrouve un producteur, elle ne dit pas qui il est.
     */
    public static MemberFileStatusDto evaluate(MemberEntity m, int validityMonths,
                                               java.util.Set<String> identityProofTypes) {
        return evaluate(m, validityMonths, identityProofTypes, LocalDate.now());
    }

    /**
     * Variante datée : la péremption se juge à {@code asOf}, pas à
     * aujourd'hui. Indispensable au rejeu d'une saisie de terrain : un
     * dossier valable le jour de l'achat ne doit pas bloquer le reçu
     * parce que la synchronisation est arrivée après son échéance.
     */
    public static MemberFileStatusDto evaluate(MemberEntity m, int validityMonths,
                                               java.util.Set<String> identityProofTypes,
                                               LocalDate asOf) {
        List<MemberFileField> missing = new ArrayList<>();
        int total = 0;
        int filled = 0;

        total++;
        if (isFilled(m.lastName)) filled++; else missing.add(MemberFileField.LAST_NAME);

        total++;
        if (isFilled(m.firstName)) filled++; else missing.add(MemberFileField.FIRST_NAMES);

        total++;
        if (m.gender != null && m.gender != MemberGender.UNKNOWN) filled++;
        else missing.add(MemberFileField.GENDER);

        total++;
        if (m.birthDate != null || m.birthYear != null) filled++;
        else missing.add(MemberFileField.BIRTH_DATE);

        total++;
        if (isFilled(m.birthPlace)) filled++; else missing.add(MemberFileField.BIRTH_PLACE);

        total++;
        if (hasIdentityDocument(m, identityProofTypes)) filled++;
        else missing.add(MemberFileField.IDENTITY_DOCUMENT);

        total++;
        if (isFilled(m.phone)) filled++; else missing.add(MemberFileField.PHONE);

        total++;
        if (isFilled(m.village)) filled++; else missing.add(MemberFileField.VILLAGE);

        total++;
        if (m.sectionId != null) filled++; else missing.add(MemberFileField.SECTION);

        total++;
        if (m.parcels != null && !m.parcels.isEmpty()) filled++; else missing.add(MemberFileField.PARCEL);

        total++;
        if (hasHousehold(m.household)) filled++; else missing.add(MemberFileField.HOUSEHOLD);

        total++;
        if (m.enrolment != null && m.enrolment.censusRegistered != null) filled++;
        else missing.add(MemberFileField.CENSUS);

        total++;
        if (m.enrolment != null && m.enrolment.dataCollectedAt != null) filled++;
        else missing.add(MemberFileField.COLLECTION_DATE);

        // Personne morale : l'existence légale et le représentant s'ajoutent
        // aux critères, puisque c'est ce qu'il faut vérifier avant de payer
        // une structure plutôt qu'un individu.
        if (m.personType == MemberPersonType.LEGAL_ENTITY) {
            total++;
            if (m.legalIdentity != null && isFilled(m.legalIdentity.registrationNumber)) filled++;
            else missing.add(MemberFileField.TRADE_REGISTER);

            total++;
            if (m.legalIdentity != null && isFilled(m.legalIdentity.representativeName)) filled++;
            else missing.add(MemberFileField.LEGAL_REPRESENTATIVE);
        }

        int pct = total == 0 ? 100 : Math.round((filled * 100f) / total);

        LocalDate collectedAt = m.enrolment != null ? m.enrolment.dataCollectedAt : null;
        LocalDate expiresAt = collectedAt != null && validityMonths > 0
                ? collectedAt.plusMonths(validityMonths)
                : null;
        boolean expired = expiresAt != null && expiresAt.isBefore(asOf != null ? asOf : LocalDate.now());

        return MemberFileStatusDto.of(pct, List.copyOf(missing), expiresAt, expired);
    }

    /** Dossier exploitable pour un paiement : complet et non périmé. */
    public static boolean isUsable(MemberEntity m, int validityMonths,
                                   java.util.Set<String> identityProofTypes) {
        MemberFileStatusDto status = evaluate(m, validityMonths, identityProofTypes);
        return status.missingFieldCodes().isEmpty() && !status.expired();
    }

    private static boolean hasIdentityDocument(MemberEntity m,
                                              java.util.Set<String> identityProofTypes) {
        if (m.identityDocuments != null && m.identityDocuments.stream()
                .anyMatch(d -> d != null && isFilled(d.number) && proves(d.type, identityProofTypes))) {
            return true;
        }
        return isFilled(m.idDocNumber);
    }

    /**
     * {@code null} : aucun référentiel de types n'existe encore, toute pièce
     * compte, pour ne pas faire régresser des dossiers déjà saisis. Un
     * ensemble vide veut dire l'inverse : des types existent, aucun ne
     * prouve l'identité, et rien ne doit passer.
     */
    private static boolean proves(String type, java.util.Set<String> identityProofTypes) {
        if (identityProofTypes == null) return true;
        return type != null
                && identityProofTypes.contains(type.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean hasHousehold(MemberHousehold h) {
        return h != null && h.childrenCount != null;
    }

    private static boolean isFilled(String s) {
        return s != null && !s.isBlank();
    }
}
