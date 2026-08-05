package com.ntech.cabosse.supplier.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Fournisseur existant ressemblant à celui qu'on s'apprête à créer.
 *
 * <p>{@code matchedOn} nomme ce qui a déclenché le rapprochement
 * ({@code phone}, {@code nameExact}, {@code nameClose}, {@code city}) :
 * l'alerte doit dire pourquoi elle se déclenche, sinon elle se contourne
 * sans être lue.</p>
 */
@Schema(description = "Fournisseur existant proche de l'identité saisie")
public record SupplierDuplicateDto(
        UUID id,
        String code,
        String name,
        String phone,
        String cityName,
        boolean active,
        /** Proximité estimée, de 0 à 1. */
        double score,
        List<String> matchedOn
) {}
