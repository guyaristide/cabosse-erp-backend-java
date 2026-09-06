package com.ntech.cabosse.direction.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Le suivi des avances d'un délégué sur la campagne (CE-199) : l'argent
 * sorti face à ce que ses livraisons ont remboursé, et ce qui reste dû
 * sur ses avances encore ouvertes.
 */
public record CampaignDelegateAdvanceDto(
        UUID delegateSupplierId,
        String delegateName,
        BigDecimal advanced,
        BigDecimal reimbursed,
        BigDecimal outstanding
) {}
