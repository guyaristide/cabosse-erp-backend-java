package com.ntech.cabosse.accounting.service;

import java.util.Optional;
import java.util.Set;

/**
 * La règle du numéro suivant sous une racine, sans base ni service : ce
 * qui permet de la lire d'un bloc et de l'appliquer en série sans
 * relire le plan à chaque compte (demandé le 19/10/2026).
 *
 * <p>La racine se lit comme le reste du plan : sa partie significative
 * est le numéro sans ses zéros de fin, et les positions libérées par
 * ces zéros portent le rang. Sous {@code 409100} les sous-comptes vont
 * de {@code 409101} à {@code 409199} ; sous {@code 4091100000}, de
 * {@code 4091100001} à {@code 4091199999}. Le rang suivant est le plus
 * grand déjà posé plus un, un numéro ouvert à la main et laissé entre
 * deux n'est jamais repris. Quand les positions sont toutes prises, le
 * numéro s'allonge d'un rang.</p>
 */
public final class SubAccountNumbering {

    private SubAccountNumbering() {}

    /** La partie significative d'une racine : son numéro sans ses zéros de fin. */
    public static String prefixOf(String root) {
        String prefix = root.replaceFirst("0+$", "");
        return prefix.isEmpty() ? root.substring(0, 1) : prefix;
    }

    /**
     * Le numéro suivant sous la racine, sachant les numéros déjà pris
     * qui commencent par sa partie significative. Vide quand plus aucun
     * numéro ne tient en vingt caractères.
     */
    public static Optional<String> next(String root, Set<String> taken) {
        String prefix = prefixOf(root);
        int width = Math.max(1, root.length() - prefix.length());
        for (int w = width; prefix.length() + w <= 20; w++) {
            int length = prefix.length() + w;
            long max = taken.stream()
                    .filter(n -> n.length() == length && !n.equals(root) && n.startsWith(prefix))
                    .map(n -> n.substring(prefix.length()))
                    .filter(rank -> !rank.isEmpty() && rank.chars().allMatch(Character::isDigit))
                    .mapToLong(Long::parseLong)
                    .max().orElse(0);
            long next = max + 1;
            if (String.valueOf(next).length() > w) continue;
            return Optional.of(prefix + String.format("%0" + w + "d", next));
        }
        return Optional.empty();
    }
}
