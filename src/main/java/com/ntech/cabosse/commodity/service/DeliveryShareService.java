package com.ntech.cabosse.commodity.service;

import com.ntech.cabosse.commodity.dto.DeliveryShareDto;
import com.ntech.cabosse.commodity.dto.DeliveryShareRowDto;
import com.ntech.cabosse.commodity.entity.CommoditySaleEntity;
import com.ntech.cabosse.commodity.repository.CommoditySaleRepository;
import com.ntech.cabosse.certification.entity.CertificationEntity;
import com.ntech.cabosse.certification.repository.CertificationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Qui a pris quoi, et dans quelle proportion.
 *
 * <p>Le conseil d'administration tient ce tableau à la main depuis des
 * années, sur un classeur : une ligne par client, le volume, la part du
 * total (demandé le 03/10/2026). Il en tient un second par label de
 * certification, et les mêmes colonnes pour une autre filière quand la
 * structure en a plusieurs.</p>
 *
 * <p>Deux répartitions d'un même volume, rendues ensemble : les demander
 * séparément ferait deux lectures de la base pour un seul tableau, et
 * laisserait les totaux diverger d'un arrondi.</p>
 *
 * <p>Le tableau de bord de campagne produit déjà ces parts, mais pour une
 * seule campagne et sous le droit de la direction. Une saison se joue en
 * une principale et ses intermédiaires, et le conseil les regarde aussi
 * cumulées : c'est pourquoi cet état prend un ensemble de campagnes.</p>
 */
@ApplicationScoped
public class DeliveryShareService {

    @Inject CommoditySaleRepository sales;
    @Inject CertificationRepository certifications;

    /**
     * @param campaignIds campagnes retenues ; vide, toute l'histoire
     * @param articleId   type de produit, ou {@code null} pour tous
     */
    public DeliveryShareDto shares(List<UUID> campaignIds, UUID articleId) {
        List<UUID> scope = campaignIds == null ? List.of()
                : campaignIds.stream().filter(Objects::nonNull).distinct().toList();

        // Une lecture par campagne plutôt qu'un filtre à plusieurs
        // valeurs : le dépôt n'expose que la campagne unique, et un
        // ensemble de campagnes se compte sur les doigts d'une main.
        List<CommoditySaleEntity> retained = new ArrayList<>();
        if (scope.isEmpty()) {
            retained.addAll(sales.listAll(null));
        } else {
            for (UUID id : scope) retained.addAll(sales.listAll(id));
        }
        if (articleId != null) {
            retained.removeIf(s -> !articleId.equals(s.articleId));
        }

        Map<UUID, BigDecimal> byCustomer = new LinkedHashMap<>();
        Map<UUID, String> customerNames = new LinkedHashMap<>();
        Map<String, BigDecimal> byLabel = new LinkedHashMap<>();
        Map<String, String> labelRaw = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        String unit = null;

        for (CommoditySaleEntity s : retained) {
            // Le poids accepté par le client, celui qui sert de base à la
            // facturation. Le déclaré dirait ce qui est parti, pas ce qui
            // a été reconnu.
            BigDecimal accepted = s.weights != null ? nz(s.weights.acceptedKg) : BigDecimal.ZERO;
            total = total.add(accepted);
            if (unit == null && s.articleUnit != null) unit = s.articleUnit;

            if (s.customerId != null) {
                byCustomer.merge(s.customerId, accepted, BigDecimal::add);
                customerNames.putIfAbsent(s.customerId, s.customerName);
            }
            String raw = s.logistics == null ? null : s.logistics.label;
            String key = normalize(raw);
            byLabel.merge(key, accepted, BigDecimal::add);
            labelRaw.putIfAbsent(key, raw);
        }

        // Le référentiel nomme le label quand il s'y reconnaît ; sinon la
        // saisie fait foi. Une expédition sans label reste comptée : la
        // faire disparaître ferait mentir le total.
        Map<String, CertificationEntity> known = new LinkedHashMap<>();
        for (CertificationEntity c : certifications.listAll()) {
            if (c.code != null) known.put(normalize(c.code), c);
            if (c.name != null) known.putIfAbsent(normalize(c.name), c);
        }

        List<DeliveryShareRowDto> customers = new ArrayList<>();
        byCustomer.forEach((id, weight) -> customers.add(new DeliveryShareRowDto(
                id, null, customerNames.get(id), weight, percent(weight, byCustomer))));
        customers.sort((a, b) -> b.weight().compareTo(a.weight()));

        List<DeliveryShareRowDto> labels = new ArrayList<>();
        byLabel.forEach((key, weight) -> {
            CertificationEntity match = known.get(key);
            labels.add(new DeliveryShareRowDto(
                    null,
                    match != null ? match.code : null,
                    match != null ? match.name : labelRaw.get(key),
                    weight,
                    percent(weight, byLabel)));
        });
        labels.sort((a, b) -> b.weight().compareTo(a.weight()));

        return new DeliveryShareDto(scope, articleId, unit, customers, labels, total, retained.size());
    }

    /**
     * La part d'une ligne dans son propre tableau.
     *
     * <p>Rapportée au total du tableau et non au volume général : une
     * expédition sans client ne figure pas dans la répartition par
     * client, et les parts n'y atteindraient jamais cent.</p>
     */
    private static BigDecimal percent(BigDecimal weight, Map<?, BigDecimal> all) {
        BigDecimal sum = all.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() == 0) return BigDecimal.ZERO;
        return weight.multiply(BigDecimal.valueOf(100))
                .divide(sum, 2, RoundingMode.HALF_UP);
    }

    /** Deux écritures d'un même label se rejoignent sur cette forme. */
    private static String normalize(String raw) {
        if (raw == null) return "";
        String stripped = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
