package com.ntech.cabosse.shared.export;

import java.util.function.Function;

/**
 * Définition d'une colonne d'export — libellé visible + extracteur de
 * valeur depuis une ligne de type {@code T}.
 *
 * <p>L'extracteur renvoie un objet : les writers CSV/XLSX/PDF ont chacun
 * leur stratégie de formatage. Idée :</p>
 * <ul>
 *   <li>{@code null} → cellule vide.</li>
 *   <li>{@code Number} / {@code BigDecimal} → numéro typé en XLSX,
 *       formaté FR en CSV/PDF.</li>
 *   <li>{@code LocalDate} / {@code Instant} → date typée en XLSX,
 *       formatée FR ailleurs.</li>
 *   <li>Tout autre objet → {@code toString()}.</li>
 * </ul>
 *
 * @param <T> type de la ligne source (entité ou DTO)
 */
public record ExportColumn<T>(
        String header,
        Function<T, Object> extractor
) {

    public static <T> ExportColumn<T> of(String header, Function<T, Object> extractor) {
        return new ExportColumn<>(header, extractor);
    }
}
