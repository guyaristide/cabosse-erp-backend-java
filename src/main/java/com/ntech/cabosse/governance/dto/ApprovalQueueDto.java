package com.ntech.cabosse.governance.dto;

import com.ntech.cabosse.shared.api.Pagination;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.math.BigDecimal;

/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 * Le propos d'ensemble du domaine vit dans le javadoc du service.
 */
/**
 * La file et ce qu'elle pèse.
 *
 * <p>Le total porte sur tout ce qui attend après filtrage, jamais sur
 * la page affichée : un conseil qui lirait le total de la première
 * page sur cinq croirait connaître l'engagement soumis.</p>
 */
@Schema(description = "File des demandes en attente de décision, et leur total")
public record ApprovalQueueDto(
        BigDecimal totalPending,
        int requestCount,
        /**
         * Ancienneté de la plus vieille demande. Un total seul ne dit
         * pas si le conseil a du retard : cinq millions déposés hier et
         * cinq millions qui attendent depuis six semaines n'appellent
         * pas la même réaction.
         */
        long oldestAgeDays,
        Pagination<PendingApprovalDto> page
) {}
