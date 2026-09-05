package com.ntech.cabosse.treasury.dto;

import com.ntech.cabosse.shared.api.Pagination;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.math.BigDecimal;

/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 * Le propos d'ensemble du domaine vit dans le javadoc du service.
 */
/**
 * La file et son total.
 *
 * <p>Le total porte sur <b>tout</b> ce qui est dû après filtrage, pas
 * sur la page affichée : un caissier qui lit « 4 200 000 » en bas de la
 * première page sur dix croirait connaître son besoin de trésorerie.</p>
 */
@Schema(description = "File des engagements à payer, et le total dû")
public record PayableQueueDto(
        BigDecimal totalRemaining,
        int beneficiaryCount,
        /**
         * Ancienneté de la plus vieille ligne de la file, tous filtres
         * appliqués. Un total seul ne dit pas si la structure a du
         * retard : cinq millions dus depuis hier et cinq millions dus
         * depuis six semaines n'appellent pas la même décision.
         */
        long oldestAgeDays,
        Pagination<PayableDto> page
) {}
