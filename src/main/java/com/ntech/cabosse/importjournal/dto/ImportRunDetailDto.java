package com.ntech.cabosse.importjournal.dto;

import com.ntech.cabosse.importjournal.entity.ImportDecision;
import com.ntech.cabosse.importjournal.entity.ImportRejection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Le déroulé complet d'un import.
 *
 * <p>Les refus regroupés par motif d'abord : sur un fichier entièrement
 * rejeté, savoir que trente-sept lignes sur trente-huit le sont pour la
 * même raison vaut mieux que trente-sept lignes à lire une par une.</p>
 */
@Schema(description = "Le déroulé d'un import, refus et décisions compris")
public record ImportRunDetailDto(
        ImportRunSummaryDto summary,
        List<ReasonGroupDto> reasons,
        List<ImportRejection> rejections,
        List<ImportDecision> decisions,
        /**
         * Vrai quand le détail des refus a été borné : le compteur du
         * résumé fait foi sur l'ampleur, cette liste sur les exemples.
         */
        boolean rejectionsTruncated
) {}
