package com.ntech.cabosse.tracabilite.dto;

import java.math.BigDecimal;
import java.util.List;

/** Lot produit à partir du lot mère. */
public record DaughterLotDto(
        String ref,
        String produit,
        BigDecimal quantity,
        String unit,
        List<String> destinations
) {}
