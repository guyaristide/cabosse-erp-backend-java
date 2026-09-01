package com.ntech.cabosse.notification.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.notification.dto.ProviderResponseDto;
import com.ntech.cabosse.notification.dto.ProviderUpsertDto;
import com.ntech.cabosse.notification.engine.EngineParam;
import com.ntech.cabosse.notification.engine.ProviderEnginePort;
import com.ntech.cabosse.notification.engine.ProviderEngineRegistry;
import com.ntech.cabosse.notification.engine.SendOutcome;
import com.ntech.cabosse.notification.engine.SendRequest;
import com.ntech.cabosse.notification.entity.NotificationProviderEntity;
import com.ntech.cabosse.notification.entity.ProviderUsage;
import com.ntech.cabosse.notification.repository.NotificationProviderRepository;
import com.ntech.cabosse.settings.service.SecretCipher;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Gestion des passerelles depuis le back-office : création, modification,
 * essai. Le contrôle de ce qui est utilisable vit ici, pas dans l'écran.
 */
@ApplicationScoped
public class ProviderAdminService {

    /** Sentinelle d'effacement explicite d'un secret. */
    private static final String CLEAR_SENTINEL = "<<clear>>";

    @Inject NotificationProviderRepository repo;
    @Inject ProviderEngineRegistry engines;
    @Inject ProviderResolver resolver;
    @Inject SecretCipher cipher;

    /**
     * Les fournisseurs d'un niveau.
     *
     * @param tenantId structure concernée, ou {@code null} pour ceux de la
     *                 plateforme. Une coopérative ne voit jamais que les
     *                 siens : mêler les niveaux lui montrerait les
     *                 identifiants de l'éditeur.
     */
    public List<ProviderResponseDto> list(UUID tenantId) {
        List<NotificationProviderEntity> rows = tenantId == null
                ? repo.listOfPlatform()
                : repo.listOfTenant(tenantId);
        return rows.stream().map(this::describe).toList();
    }

    public ProviderResponseDto get(UUID id, UUID tenantId) {
        return describe(load(id, tenantId));
    }

    public ProviderResponseDto create(ProviderUpsertDto payload, String actor, UUID tenantId) {
        ProviderEnginePort engine = requireEngine(payload.engineCode());
        NotificationProviderEntity e = new NotificationProviderEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.engineCode = engine.code();
        e.channel = engine.channel();
        e.scope = tenantId == null
                ? com.ntech.cabosse.notification.entity.ProviderScope.PLATFORM
                : com.ntech.cabosse.notification.entity.ProviderScope.TENANT;
        e.tenantId = tenantId;
        e.createdAt = Instant.now();
        apply(e, payload, engine, actor);
        repo.insert(e);
        return describe(e);
    }

    public ProviderResponseDto update(UUID id, ProviderUpsertDto payload, String actor,
                                      UUID tenantId) {
        NotificationProviderEntity e = load(id, tenantId);
        ProviderEnginePort engine = requireEngine(payload.engineCode());
        if (!engine.code().equals(e.engineCode)) {
            // Changer de moteur revient à changer de contrat de paramètres :
            // les valeurs enregistrées n'auraient plus le même sens.
            throw new BusinessException(Messages.msg("m.ntf-engine-immutable"));
        }
        apply(e, payload, engine, actor);
        repo.replace(e);
        return describe(e);
    }

    public void delete(UUID id, UUID tenantId) {
        load(id, tenantId);
        repo.delete(id);
    }

    /**
     * Essaie la passerelle sur une cible donnée et rend le motif tel que
     * l'opérateur l'a formulé. C'est la seule façon pour un administrateur
     * de distinguer « clé révoquée » de « émetteur non déclaré ».
     */
    public TestResult test(UUID id, String target, UUID tenantId) {
        if (target == null || target.isBlank()) {
            throw new BusinessException(Messages.msg("m.ntf-test-target-required"));
        }
        NotificationProviderEntity e = load(id, tenantId);
        Optional<ResolvedProvider> resolved = resolver.resolveOne(e);
        if (resolved.isEmpty()) {
            return new TestResult(false, unusableReason(e));
        }
        ResolvedProvider provider = resolved.get();
        SendRequest request = new SendRequest(
                e.channel, target.trim(),
                "Essai de configuration Cabosse ERP",
                "Ceci est un message d'essai envoyé depuis la console d'administration "
                        + "de Cabosse ERP par NEIBA Technologies.");
        try {
            SendOutcome outcome = provider.engine().send(request, provider.params());
            return new TestResult(outcome.success(),
                    outcome.success() ? outcome.providerMessageId() : outcome.failureReason());
        } catch (Exception ex) {
            return new TestResult(false,
                    com.ntech.cabosse.notification.engine.NotificationHttpClient.describe(ex));
        }
    }

    public record TestResult(boolean success, String detail) {}

    // ─── Interne ────────────────────────────────────────────────────

    private void apply(NotificationProviderEntity e, ProviderUpsertDto payload,
                       ProviderEnginePort engine, String actor) {
        e.label = payload.label().trim();
        e.active = payload.active();
        e.updatedAt = Instant.now();
        e.updatedBy = actor;
        e.params = mergeParams(e.params, payload.params(), engine);
        e.secretKeys = engine.declaredParams().stream()
                .filter(EngineParam::secret)
                .map(EngineParam::code)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        e.usages = normalizeUsages(payload.usages());
    }

    /**
     * Fusionne les valeurs reçues avec celles déjà enregistrées. Un secret
     * vide conserve l'ancienne valeur (l'écran renvoie des masques) ;
     * la sentinelle l'efface.
     */
    private Map<String, String> mergeParams(Map<String, String> existing,
                                             Map<String, String> incoming,
                                             ProviderEnginePort engine) {
        Map<String, String> previous = existing != null ? existing : Map.of();
        Map<String, String> received = incoming != null ? incoming : Map.of();
        Set<String> secretCodes = engine.declaredParams().stream()
                .filter(EngineParam::secret).map(EngineParam::code)
                .collect(java.util.stream.Collectors.toSet());

        Map<String, String> stored = new HashMap<>();
        for (EngineParam declared : engine.declaredParams()) {
            String code = declared.code();
            String value = received.get(code);
            if (secretCodes.contains(code)) {
                if (CLEAR_SENTINEL.equals(value)) continue;
                if (value == null || value.isBlank()) {
                    String kept = previous.get(code);
                    if (kept != null) stored.put(code, kept);
                    continue;
                }
                stored.put(code, cipher.encrypt(value));
            } else if (value != null && !value.isBlank()) {
                stored.put(code, value.trim());
            }
        }
        return stored;
    }

    /**
     * Réécrit les rangs en bloc, à partir de l'ordre reçu. Deux
     * passerelles ne peuvent donc pas se retrouver au même rang pour un
     * usage, ce qui rendrait l'ordre d'essai indéterminé.
     */
    private List<ProviderUsage> normalizeUsages(List<ProviderUpsertDto.UsageDto> received) {
        if (received == null || received.isEmpty()) return new ArrayList<>();
        List<ProviderUpsertDto.UsageDto> sorted = new ArrayList<>(received);
        sorted.sort(java.util.Comparator.comparingInt(ProviderUpsertDto.UsageDto::priority));
        List<ProviderUsage> normalized = new ArrayList<>();
        Set<com.ntech.cabosse.notification.entity.NotificationUsage> seen = new HashSet<>();
        int rank = 0;
        for (ProviderUpsertDto.UsageDto u : sorted) {
            if (u == null || u.usage() == null || !seen.add(u.usage())) continue;
            normalized.add(new ProviderUsage(u.usage(), rank++));
        }
        return normalized;
    }

    private ProviderResponseDto describe(NotificationProviderEntity e) {
        String reason = unusableReason(e);
        return ProviderResponseDto.from(e, reason == null, reason);
    }

    /** Motif d'inutilisabilité, ou null si la passerelle peut émettre. */
    private String unusableReason(NotificationProviderEntity e) {
        Optional<ProviderEnginePort> engine = engines.find(e.engineCode);
        if (engine.isEmpty()) {
            return "Moteur « " + e.engineCode + " » absent de cette version de la plateforme.";
        }
        if (e.usages == null || e.usages.isEmpty()) {
            return "Aucun usage rattaché : cette passerelle ne sera jamais choisie.";
        }
        try {
            if (resolver.resolveOne(e).isEmpty()) {
                return "Paramètre requis manquant ou secret illisible.";
            }
        } catch (Exception ex) {
            return "Configuration illisible.";
        }
        return null;
    }

    private ProviderEnginePort requireEngine(String code) {
        return engines.find(code).orElseThrow(() -> new BusinessException(
                Messages.msg("m.ntf-engine-unknown", code,
                        engines.all().stream().map(ProviderEnginePort::code).toList())));
    }

    /**
     * Charge un fournisseur en vérifiant qu'il appartient bien au niveau
     * qui le demande.
     *
     * <p>Une structure qui viserait l'identifiant d'une autre, ou celui de
     * la plateforme, obtient un « introuvable » et non un refus : lui dire
     * qu'il existe lui apprendrait déjà quelque chose.</p>
     */
    private NotificationProviderEntity load(UUID id, UUID tenantId) {
        NotificationProviderEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.ntf-provider-not-found", id)));
        boolean mine = tenantId == null
                ? e.tenantId == null
                : tenantId.equals(e.tenantId);
        if (!mine) {
            throw new NotFoundException(Messages.msg("m.ntf-provider-not-found", id));
        }
        return e;
    }
}
