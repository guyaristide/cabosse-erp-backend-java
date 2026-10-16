package com.ntech.cabosse.shared.imports;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Les écritures sous lesquelles un même tiers se présente dans un
 * fichier du terrain.
 *
 * <p>Le rapprochement se faisait au nom exact, à la casse près. Le
 * terrain, lui, écrit « TRAORE MOUSSA (DIOUROUZON) » pour un délégué que
 * le registre tient sous son seul nom, et « IVORY COCOA PRODUCTS (ICP) »
 * pour un client enregistré sous son sigle. Dans les deux cas la ligne
 * ressortait sans tiers, et rien ne disait que la parenthèse en était la
 * cause (relevé le 16/10/2026).</p>
 *
 * <p>Trois écritures, et pas une de plus : le nom tel quel, le nom sans
 * sa parenthèse de fin, et le contenu de cette parenthèse quand il tient
 * lieu de sigle. Chacune se compare accents, ponctuation et casse
 * retirés. Rien n'est deviné au-delà : c'est au candidat unique de
 * trancher, jamais au plus ressemblant.</p>
 */
public final class NameAliases {

    private NameAliases() {}

    /** Les clés sous lesquelles chercher, ou indexer, un même nom. */
    public static List<String> of(String raw) {
        String name = ImportFields.clean(raw);
        if (name == null) return List.of();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        add(keys, name);

        int open = name.lastIndexOf('(');
        int close = name.lastIndexOf(')');
        if (open > 0 && close > open) {
            add(keys, name.substring(0, open));
            add(keys, name.substring(open + 1, close));
        }
        return new ArrayList<>(keys);
    }

    /** Accents, ponctuation et casse retirés : seuls les mots comptent. */
    public static String fold(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static void add(LinkedHashSet<String> keys, String candidate) {
        String folded = fold(candidate);
        if (!folded.isEmpty()) keys.add(folded);
    }
}
