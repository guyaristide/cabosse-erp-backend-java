package com.ntech.cabosse.members.register;

import java.math.BigDecimal;

/**
 * Ligne du registre producteurs (backlog REG-01). Une ligne par parcelle
 * (le producteur se répète), au format du fichier modèle. Valeurs déjà
 * résolues et dénormalisées (section, région, département, agent, campagne).
 */
public record RegisterRow(
        int no,
        String coopName,
        String producerCode,
        String externalCode,
        String producerName,
        String birth,
        String idDocType,
        String idDocNumber,
        String sexe,
        String section,
        String village,
        String plantationCode,
        Integer plantingYear,
        Integer age,
        BigDecimal surfaceHa,
        BigDecimal yieldPerHa,
        BigDecimal estimateKg,
        Double latitude,
        Double longitude,
        String department,
        String region,
        String agentName,
        String agentContact
) {}
