package com.ntech.cabosse.producerpurchase.service;

import com.ntech.cabosse.accounting.dto.JournalPieceResponseDto;
import com.ntech.cabosse.accounting.entity.PostingSourceType;
import com.ntech.cabosse.accounting.service.AccountingQueryService;
import com.ntech.cabosse.producerpurchase.dto.CollectionEntryDto;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Les écritures nées d'une réception, avec ce que le reçu porte.
 *
 * <p>L'écran lisait le journal filtré sur les natures de réception, donc
 * une date, une pièce et un montant. Le montant seul ne se vérifie pas :
 * 240 000 F ne veut rien dire tant qu'on ne voit pas le producteur, les
 * sacs et le poids net qui le fondent, et le reçu les porte (demandé le
 * 06/10/2026).</p>
 *
 * <p>Les reçus se chargent en une requête pour toute la page : un appel
 * par ligne ferait autant d'allers-retours que de lignes affichées.</p>
 */
@ApplicationScoped
public class CollectionEntriesService {

    /** Les natures que la collecte produit, et elles seules. */
    public static final List<String> SOURCE_TYPES = List.of(
            PostingSourceType.PRODUCER_PURCHASE.name(),
            PostingSourceType.DIRECT_RECEIPT.name());

    @Inject AccountingQueryService journal;
    @Inject ProducerPurchaseRepository purchases;

    public long count(LocalDate from, LocalDate to) {
        return journal.countJournal(from, to, null, null, SOURCE_TYPES);
    }

    public List<CollectionEntryDto> list(LocalDate from, LocalDate to, int page, int perPage) {
        List<JournalPieceResponseDto> pieces =
                journal.listJournal(from, to, null, null, SOURCE_TYPES, page, perPage);

        Set<UUID> receiptIds = pieces.stream()
                .filter(p -> p.sourceType() == PostingSourceType.PRODUCER_PURCHASE)
                .map(JournalPieceResponseDto::sourceId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, ProducerPurchaseEntity> receipts = purchases.findByIds(receiptIds).stream()
                .collect(Collectors.toMap(r -> r.id, Function.identity(), (a, b) -> a));

        return pieces.stream().map(p -> {
            ProducerPurchaseEntity r = p.sourceId() != null ? receipts.get(p.sourceId()) : null;
            // Une réception directe n'a pas de reçu producteur : ses trois
            // colonnes restent vides plutôt qu'à zéro, zéro sac se lisant
            // comme une livraison vide.
            return new CollectionEntryDto(
                    p.id(), p.ref(), p.date(), p.sourceType(), p.sourceId(), p.sourceRef(),
                    p.libelle(), p.totalDebit(),
                    r != null ? r.producerName : null,
                    r != null ? r.delegateName : null,
                    r != null ? r.nbSacs : null,
                    r != null ? r.weightKg : null);
        }).toList();
    }
}
