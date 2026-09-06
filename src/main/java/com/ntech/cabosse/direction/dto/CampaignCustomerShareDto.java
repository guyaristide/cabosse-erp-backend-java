package com.ntech.cabosse.direction.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** La part d'un client dans les volumes vendus de la campagne (CE-198). */
public record CampaignCustomerShareDto(
        UUID customerId,
        String customerName,
        BigDecimal soldWeight
) {}
