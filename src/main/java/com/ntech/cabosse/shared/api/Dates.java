package com.ntech.cabosse.shared.api;

import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Les dates des paramètres de requête, au format ISO (aaaa-mm-jj).
 *
 * <p>Une date mal écrite ne se tait pas : laisser tomber le filtre en
 * silence élargirait la liste au lieu de la restreindre, et l'utilisateur
 * croirait voir le jour demandé.</p>
 */
public final class Dates {
    private Dates() {}

    public static LocalDate parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(Messages.msg("m.shr-date-invalid", raw.trim()));
        }
    }
}
