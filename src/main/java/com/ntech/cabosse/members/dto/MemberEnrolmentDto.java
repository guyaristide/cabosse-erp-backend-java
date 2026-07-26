package com.ntech.cabosse.members.dto;

import com.ntech.cabosse.members.entity.MemberEnrolment;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

/** Volet recensement et collecte de données (backlog MEM-09). */
@Schema(description = "Recensement du producteur et date de collecte des données")
public record MemberEnrolmentDto(
        Boolean censusRegistered,
        Boolean producerCardIssued,
        LocalDate dataCollectedAt,
        UUID dataCollectedByMemberId
) {
    public static MemberEnrolmentDto from(MemberEnrolment e) {
        if (e == null) return new MemberEnrolmentDto(null, null, null, null);
        return new MemberEnrolmentDto(e.censusRegistered, e.producerCardIssued,
                e.dataCollectedAt, e.dataCollectedByMemberId);
    }

    public MemberEnrolment toEntity() {
        MemberEnrolment e = new MemberEnrolment();
        e.censusRegistered = censusRegistered;
        e.producerCardIssued = producerCardIssued;
        e.dataCollectedAt = dataCollectedAt;
        e.dataCollectedByMemberId = dataCollectedByMemberId;
        return e;
    }
}
