package com.ntech.cabosse.members.entity;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Volet recensement et fraîcheur du dossier producteur (backlog MEM-09).
 * Sub-document de {@link MemberEntity}.
 *
 * <p>{@link #dataCollectedAt} est la date de l'enquête sur le terrain,
 * volontairement distincte de {@code updatedAt} : une correction de
 * téléphone en bureau ne rajeunit pas une enquête vieille de deux ans. La
 * péremption du dossier se calcule sur cette date, avec une durée de
 * validité paramétrable par le tenant.</p>
 */
public class MemberEnrolment {

    /** Producteur recensé auprès de l'organisme de filière. */
    public Boolean censusRegistered;

    /** Carte de producteur effectivement remise au producteur. */
    public Boolean producerCardIssued;

    /** Date de collecte des données sur le terrain. */
    public LocalDate dataCollectedAt;

    /**
     * Agent ayant collecté les données. Référence vers un autre
     * {@link MemberEntity} (l'agent est lui-même un membre), comme
     * {@link MemberEntity#followUpAgentMemberId}.
     */
    public UUID dataCollectedByMemberId;

    public MemberEnrolment() {}

    public boolean isEmpty() {
        return censusRegistered == null && producerCardIssued == null
                && dataCollectedAt == null && dataCollectedByMemberId == null;
    }
}
