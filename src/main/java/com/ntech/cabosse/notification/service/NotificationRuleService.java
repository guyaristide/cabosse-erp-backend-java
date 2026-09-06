package com.ntech.cabosse.notification.service;

import com.ntech.cabosse.notification.dto.NotificationRuleDto;
import com.ntech.cabosse.notification.dto.NotificationRuleUpdateDto;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationRuleEntity;
import com.ntech.cabosse.notification.repository.NotificationRuleRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Le moteur de réglage des notifications du tenant (CE-205) : quels
 * événements déclenchent, vers quels profils, par quels canaux, avec
 * quelles copies.
 *
 * <p>Le catalogue décrit les événements et leurs défauts ; la règle en
 * base est l'exception posée par l'administrateur. Un événement actif
 * exige au moins un canal : une notification sans chemin n'existerait
 * que dans la configuration.</p>
 */
@ApplicationScoped
public class NotificationRuleService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Inject NotificationEventCatalog catalog;
    @Inject NotificationRuleRepository rules;
    @Inject IdGenerator idGenerator;
    @Inject org.eclipse.microprofile.jwt.JsonWebToken jwt;

    public List<NotificationRuleDto> list() {
        Map<String, NotificationRuleEntity> stored = rules.listAll().stream()
                .collect(Collectors.toMap(r -> r.eventCode, Function.identity(), (a, b) -> a));
        List<NotificationRuleDto> out = new ArrayList<>();
        for (NotificationEventSpec spec : catalog.all()) {
            NotificationRuleEntity rule = stored.get(spec.code());
            NotificationRuleResolution res = resolutionOf(rule);
            out.add(new NotificationRuleDto(
                    spec.code(),
                    Messages.msg(spec.labelKey()),
                    spec.audienceConfigurable(),
                    spec.defaultAudience() != null
                            ? Messages.msg(spec.defaultAudience().messageKey())
                            : Messages.msg("m.ntf-rule-fixed-audience"),
                    res.enabled(),
                    res.channels().stream().map(Enum::name).sorted().toList(),
                    res.recipientRoleIds() != null ? res.recipientRoleIds() : List.of(),
                    res.ccEmails(),
                    rule != null,
                    rule != null ? rule.updatedAt : null,
                    rule != null ? rule.updatedByEmail : null));
        }
        return out;
    }

    public List<NotificationRuleDto> update(String eventCode, NotificationRuleUpdateDto payload) {
        NotificationEventSpec spec = catalog.find(eventCode)
                .orElseThrow(() -> new NotFoundException(
                        Messages.msg("m.ntf-rule-unknown-event", eventCode)));

        boolean enabled = payload.enabled() == null || payload.enabled();
        Set<NotificationChannel> channels = parseChannels(payload.channels());
        if (enabled && channels.isEmpty()) {
            throw new BusinessException(Messages.msg("m.ntf-rule-channel-required"));
        }
        List<String> cc = normalizeEmails(payload.ccEmails());

        NotificationRuleEntity e = rules.findByEvent(eventCode)
                .orElseGet(NotificationRuleEntity::new);
        if (e.id == null) {
            e.id = idGenerator.newId();
            e.eventCode = eventCode;
            e.createdAt = Instant.now();
        }
        e.enabled = enabled;
        e.channels = channels.stream().map(Enum::name).sorted().toList();
        e.recipientRoleIds = spec.audienceConfigurable() && payload.recipientRoleIds() != null
                && !payload.recipientRoleIds().isEmpty()
                ? List.copyOf(new LinkedHashSet<>(payload.recipientRoleIds()))
                : null;
        e.ccEmails = cc;
        e.updatedAt = Instant.now();
        try {
            e.updatedByEmail = jwt.getName();
        } catch (RuntimeException ignored) {
            e.updatedByEmail = null;
        }
        rules.upsert(e);
        return list();
    }

    /** Ce que le routeur applique pour cet événement, défauts compris. */
    public NotificationRuleResolution resolution(String eventCode) {
        return resolutionOf(rules.findByEvent(eventCode).orElse(null));
    }

    private NotificationRuleResolution resolutionOf(NotificationRuleEntity rule) {
        if (rule == null) return NotificationRuleResolution.defaults();
        Set<NotificationChannel> channels = EnumSet.noneOf(NotificationChannel.class);
        if (rule.channels != null) {
            for (String c : rule.channels) {
                try {
                    channels.add(NotificationChannel.valueOf(c));
                } catch (IllegalArgumentException ignored) {
                    // Un canal disparu du code ne casse pas la règle.
                }
            }
        }
        if (channels.isEmpty()) {
            channels = EnumSet.of(NotificationChannel.EMAIL, NotificationChannel.IN_APP);
        }
        return new NotificationRuleResolution(
                rule.enabled,
                Set.copyOf(channels),
                rule.recipientRoleIds != null && !rule.recipientRoleIds.isEmpty()
                        ? rule.recipientRoleIds : null,
                rule.ccEmails != null ? rule.ccEmails : List.of());
    }

    private Set<NotificationChannel> parseChannels(List<String> raw) {
        Set<NotificationChannel> out = EnumSet.noneOf(NotificationChannel.class);
        if (raw == null) return out;
        for (String c : raw) {
            if (c == null || c.isBlank()) continue;
            Optional<NotificationChannel> channel = parse(c.trim().toUpperCase());
            out.add(channel.orElseThrow(() -> new BusinessException(
                    Messages.msg("m.ntf-rule-unknown-channel", c))));
        }
        return out;
    }

    private static Optional<NotificationChannel> parse(String value) {
        try {
            NotificationChannel channel = NotificationChannel.valueOf(value);
            // Le push attend son moteur (phase 2) : le proposer serait
            // promettre un canal qui n'envoie rien.
            return channel == NotificationChannel.PUSH ? Optional.empty() : Optional.of(channel);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static List<String> normalizeEmails(List<String> raw) {
        if (raw == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String email : raw) {
            if (email == null || email.isBlank()) continue;
            String trimmed = email.trim().toLowerCase();
            if (!EMAIL.matcher(trimmed).matches()) {
                throw new BusinessException(Messages.msg("m.ntf-rule-invalid-email", email));
            }
            out.add(trimmed);
        }
        return List.copyOf(out);
    }
}
