package com.ntech.cabosse.logs.service;

import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.ws.rs.BadRequestException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * La période demandée pour une archive, bornes comprises.
 *
 * <p>Les deux bornes sont obligatoires : une archive sans borne
 * emporterait deux ans de traces sans que personne l'ait voulu. Une borne
 * s'écrit en instant ISO-8601 ou en simple date ; une date de fin vaut
 * alors jusqu'à la fin de ce jour, en temps universel.</p>
 */
record LogsPeriod(Instant from, Instant to) {

    static LogsPeriod parse(String fromRaw, String toRaw) {
        if (isBlank(fromRaw) || isBlank(toRaw)) {
            throw new BadRequestException(Messages.msg("m.log-archive-period-required"));
        }
        Instant from = parse(fromRaw.trim(), false);
        Instant to = parse(toRaw.trim(), true);
        if (from.isAfter(to)) {
            throw new BadRequestException(Messages.msg("m.log-archive-period-inverted"));
        }
        return new LogsPeriod(from, to);
    }

    private static Instant parse(String raw, boolean endOfDay) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException notAnInstant) {
            try {
                LocalDate day = LocalDate.parse(raw);
                return endOfDay
                        ? day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1)
                        : day.atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException notADate) {
                throw new BadRequestException(Messages.msg("m.log-archive-period-invalid", raw));
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
