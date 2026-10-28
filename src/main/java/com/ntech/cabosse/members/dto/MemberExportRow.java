package com.ntech.cabosse.members.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Une ligne de l'export de la liste des membres, telle que l'écran la
 * filtre (demandé le 28/10/2026) : ce que la grille montre, rien de plus.
 */
public record MemberExportRow(
        String code,
        String name,
        String status,
        String gender,
        String sectionName,
        String village,
        String phone,
        String collector,
        int parcelCount,
        BigDecimal partsSocialesAmount,
        LocalDate joinedAt
) {}
