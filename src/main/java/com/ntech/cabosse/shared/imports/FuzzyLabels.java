package com.ntech.cabosse.shared.imports;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;

/**
 * Rapprochement tolérant de libellés saisis à la main.
 *
 * <p>Un fichier d'import vient de plusieurs mains et de plusieurs années :
 * « Carte nationale d'identité », « carte nationnale d'identite », « CNI »
 * et « C.N.I. » désignent la même chose. Sans rapprochement, le référentiel
 * se remplit de doublons et les filtres deviennent inutilisables.</p>
 *
 * <p>La distance d'édition est bornée en proportion de la longueur : sur un
 * libellé court, une lettre de différence est une faute de frappe ; sur un
 * libellé long, on tolère davantage sans jamais confondre deux termes
 * réellement distincts.</p>
 */
public final class FuzzyLabels {

    /**
     * Tolérance aux fautes, fonction de la longueur. Aucune sur un libellé
     * court : « CNI » et « NNI » ne diffèrent que d'une lettre et désignent
     * deux pièces distinctes. Une faute admise à partir de six caractères,
     * deux au-delà de douze, jamais plus : passé ce seuil, on ne corrige
     * plus une frappe, on invente une correspondance.
     */
    private static int allowedEdits(int length) {
        if (length < 6) return 0;
        return length < 12 ? 1 : 2;
    }

    private FuzzyLabels() {}

    /** Minuscules, sans accents ni ponctuation, espaces normalisés. */
    public static String canonical(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    /** Vrai si les deux libellés désignent vraisemblablement la même chose. */
    public static boolean matches(String a, String b) {
        String ca = canonical(a);
        String cb = canonical(b);
        if (ca.isEmpty() || cb.isEmpty()) return false;
        if (ca.equals(cb)) return true;
        int allowed = allowedEdits(Math.max(ca.length(), cb.length()));
        return allowed > 0 && distance(ca, cb) <= allowed;
    }

    /**
     * Meilleur candidat correspondant au libellé saisi.
     *
     * @param raw        libellé du fichier
     * @param candidates libellés déjà connus (référentiel du tenant)
     * @return le candidat retenu, ou null si aucun ne correspond
     */
    public static String bestMatch(String raw, Collection<String> candidates) {
        String canonicalRaw = canonical(raw);
        if (canonicalRaw.isEmpty()) return null;
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            String canonicalCandidate = canonical(candidate);
            if (canonicalCandidate.isEmpty()) continue;
            if (canonicalCandidate.equals(canonicalRaw)) return candidate;
            int allowed = allowedEdits(Math.max(canonicalRaw.length(), canonicalCandidate.length()));
            if (allowed == 0) continue;
            int d = distance(canonicalRaw, canonicalCandidate);
            if (d <= allowed && d < bestDistance) {
                best = candidate;
                bestDistance = d;
            }
        }
        return best;
    }

    /** Distance de Levenshtein, sur deux lignes de travail seulement. */
    public static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
