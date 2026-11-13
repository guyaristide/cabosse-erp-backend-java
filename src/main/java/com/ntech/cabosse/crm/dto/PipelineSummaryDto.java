package com.ntech.cabosse.crm.dto;

import com.ntech.cabosse.crm.entity.ProspectStage;

import java.util.Map;

/** Le pipeline d'un coup d'œil : combien à chaque étape, combien d'actions dues. */
public record PipelineSummaryDto(
        Map<ProspectStage, Long> byStage,
        /** Prospects ouverts dont l'action prévue est passée ou due aujourd'hui. */
        long dueCount
) {}
