package com.ntech.cabosse.members.service;

import com.ntech.cabosse.members.dto.MemberExportRow;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.members.repository.MemberSearchCriteria;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * L'export de la liste des membres, avec les filtres de l'écran.
 *
 * <p>Seul le registre producteurs s'exportait, sur tout le sociétariat et
 * une ligne par parcelle. Ici c'est la liste telle qu'on la regarde :
 * mêmes critères que la page, toutes les pages à la suite, triée par nom
 * (demandé le 28/10/2026).</p>
 */
@ApplicationScoped
public class MemberListExportService {

    @Inject MemberRepository members;
    @Inject SectionRepository sections;

    public List<MemberExportRow> rows(MemberSearchCriteria criteria) {
        Map<UUID, String> sectionNames = new HashMap<>();
        sections.listAll().forEach(s -> sectionNames.put(s.id, s.name));
        return members.search(criteria, 0, Integer.MAX_VALUE).stream()
                .sorted(Comparator.comparing((MemberEntity m) -> m.name == null ? "" : m.name,
                        String.CASE_INSENSITIVE_ORDER))
                .map(m -> new MemberExportRow(
                        m.code,
                        m.name,
                        statusLabel(m.status),
                        genderLabel(m.gender),
                        m.sectionId == null ? null : sectionNames.get(m.sectionId),
                        m.village,
                        m.phone,
                        Messages.msg(m.collector ? "m.exp-yes" : "m.exp-no"),
                        m.parcels == null ? 0 : m.parcels.size(),
                        m.partsSocialesAmount,
                        m.joinedAt))
                .toList();
    }

    // Des clés littérales, que le catalogue vérifie une à une : une clé
    // composée à la volée lui échapperait.
    private static String statusLabel(com.ntech.cabosse.members.entity.MemberStatus status) {
        if (status == null) return null;
        return switch (status) {
            case PENDING -> Messages.msg("m.exp-mbr-status-PENDING");
            case ACTIVE -> Messages.msg("m.exp-mbr-status-ACTIVE");
            case SUSPENDED -> Messages.msg("m.exp-mbr-status-SUSPENDED");
            case INACTIVE -> Messages.msg("m.exp-mbr-status-INACTIVE");
            case RETIRED -> Messages.msg("m.exp-mbr-status-RETIRED");
        };
    }

    private static String genderLabel(com.ntech.cabosse.members.entity.MemberGender gender) {
        if (gender == null) return null;
        return switch (gender) {
            case MALE -> Messages.msg("m.exp-mbr-gender-MALE");
            case FEMALE -> Messages.msg("m.exp-mbr-gender-FEMALE");
            case UNKNOWN -> Messages.msg("m.exp-mbr-gender-UNKNOWN");
        };
    }
}
