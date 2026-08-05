package com.ntech.cabosse.supplier.service;

import com.ntech.cabosse.supplier.dto.SupplierDuplicateDto;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Détection des fournisseurs déjà enregistrés qui ressemblent à celui
 * qu'on s'apprête à créer (backlog ACH-08, exigence EF-03).
 *
 * <p>Sans codification fiable, le même apporteur finit par exister deux
 * fois : une orthographe différente, un prénom et un nom inversés, et les
 * synthèses de fin de campagne comptent deux fournisseurs là où il y en a
 * un, chacun avec la moitié de ses apports.</p>
 *
 * <p>Trois signaux, dans l'ordre où ils tranchent. Le <strong>téléphone</strong>
 * est le plus sûr : deux personnes ne partagent pas un numéro. Le
 * <strong>nom</strong> se compare par mots plutôt que caractère à
 * caractère, parce que « Kouassi Yao » et « Yao Kouassi » désignent la même
 * personne là où une distance de chaîne les éloignerait. La
 * <strong>localité</strong> ne suffit jamais seule mais confirme un nom
 * approchant.</p>
 *
 * <p>Rien n'est bloqué : deux frères peuvent porter le même nom dans le
 * même village. C'est une alerte, tranchée par la personne qui saisit.</p>
 */
@ApplicationScoped
public class SupplierDuplicateDetector {

    /**
     * En deçà, deux noms sont trop éloignés pour mériter une alerte. Fixé
     * à un mot commun sur deux : c'est le cas du nom de famille partagé,
     * celui que le cahier des charges nomme quand il parle d'homonymes.
     */
    private static final double NAME_SIMILARITY_FLOOR = 0.5;

    /** Un nom approchant seul suffit à alerter à partir de ce seuil. */
    private static final double NAME_SIMILARITY_STRONG = 0.8;

    /** Nombre de candidats présentés : au delà, la liste cesse d'aider. */
    private static final int MAX_CANDIDATES = 5;

    @Inject SupplierRepository repo;

    /**
     * Fournisseurs ressemblant à l'identité proposée, du plus probable au
     * moins probable.
     *
     * @param excludeId fiche en cours de modification, à ne pas se voir
     *                  proposer comme son propre doublon
     */
    public List<SupplierDuplicateDto> search(String name, String phone, String cityName,
                                             UUID excludeId) {
        Set<String> tokens = tokens(name);
        String normalizedPhone = digits(phone);
        String normalizedCity = normalize(cityName);
        if (tokens.isEmpty() && normalizedPhone.isEmpty()) return List.of();

        List<SupplierDuplicateDto> found = new ArrayList<>();
        for (SupplierEntity e : repo.listAll()) {
            if (excludeId != null && excludeId.equals(e.id)) continue;

            List<String> reasons = new ArrayList<>();
            boolean samePhone = !normalizedPhone.isEmpty()
                    && normalizedPhone.equals(digits(e.phone));
            if (samePhone) reasons.add("phone");

            double nameScore = similarity(tokens, tokens(e.name));
            if (nameScore >= 1.0) reasons.add("nameExact");
            else if (nameScore >= NAME_SIMILARITY_FLOOR) reasons.add("nameClose");

            boolean sameCity = !normalizedCity.isEmpty()
                    && normalizedCity.equals(normalize(e.cityName));
            if (sameCity && nameScore >= NAME_SIMILARITY_FLOOR) reasons.add("city");

            // Un nom vaguement approchant ne justifie une alerte que s'il
            // est confirmé par le téléphone ou la localité. Sinon, la
            // moitié du registre remonterait à chaque saisie.
            boolean alert = samePhone
                    || nameScore >= NAME_SIMILARITY_STRONG
                    || (nameScore >= NAME_SIMILARITY_FLOOR && sameCity);
            if (!alert) continue;

            found.add(new SupplierDuplicateDto(
                    e.id, e.code, e.name, e.phone, e.cityName, e.active,
                    score(samePhone, nameScore, sameCity), reasons));
        }
        found.sort((a, b) -> Double.compare(b.score(), a.score()));
        return found.size() > MAX_CANDIDATES ? found.subList(0, MAX_CANDIDATES) : found;
    }

    private static double score(boolean samePhone, double nameScore, boolean sameCity) {
        double s = nameScore * 0.6;
        if (samePhone) s += 0.35;
        if (sameCity) s += 0.05;
        return Math.min(1.0, Math.round(s * 100) / 100.0);
    }

    /**
     * Proximité de deux noms par les mots qu'ils partagent, l'ordre étant
     * indifférent. Un mot commun sur deux donne 0,5.
     */
    static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        long shared = a.stream().filter(b::contains).count();
        if (shared == 0) return 0;
        return (double) shared / Math.max(a.size(), b.size());
    }

    /** Mots signifiants d'un nom, accents et ponctuation retirés. */
    static Set<String> tokens(String raw) {
        String n = normalize(raw);
        if (n.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String token : n.split(" ")) {
            // Les particules et initiales n'apportent rien et rapprocheraient
            // des noms qui n'ont rien à voir.
            if (token.length() > 1) out.add(token);
        }
        return out;
    }

    static String normalize(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    /**
     * Chiffres d'un numéro, réduits aux huit derniers : un même numéro
     * saisi avec et sans indicatif reste le même numéro.
     */
    static String digits(String raw) {
        if (raw == null) return "";
        String d = raw.replaceAll("\\D", "");
        return d.length() > 8 ? d.substring(d.length() - 8) : d;
    }

    /** Découpe utilitaire exposée pour les tests de normalisation. */
    static List<String> words(String raw) {
        return Arrays.stream(normalize(raw).split(" ")).filter(w -> !w.isEmpty()).toList();
    }
}
