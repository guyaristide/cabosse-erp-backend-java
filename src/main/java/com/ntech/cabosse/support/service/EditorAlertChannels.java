package com.ntech.cabosse.support.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntech.cabosse.settings.dto.EditorAlertsDto;
import com.ntech.cabosse.settings.dto.EditorAlertsUpsertDto;
import com.ntech.cabosse.settings.service.PlatformSettingsService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Les canaux par lesquels l'éditeur apprend ce qui se passe à
 * l'assistance : courriel, Slack, webhook, autant qu'on veut, chacun
 * abonné aux événements qu'il veut (demandé le 24/10/2026).
 *
 * <p>Deux champs isolés disaient la même chose avant : une boîte
 * d'assistance dans l'onglet courriel, puis un webhook Slack à côté. La
 * liste les remplace. Tant qu'elle n'a jamais été enregistrée, elle se
 * déduit de ces deux champs, pour que rien ne cesse de sonner le jour de
 * la mise à jour.</p>
 *
 * <p>Les adresses de webhook sont des secrets : chiffrées au repos, jamais
 * renvoyées au navigateur, conservées quand l'écran les laisse vides.</p>
 */
@ApplicationScoped
public class EditorAlertChannels {

    public static final String SECTION = "editorAlerts";
    static final String CHANNELS_KEY = "channels";
    static final String TARGET_PREFIX = "target.";

    public enum Type { EMAIL, SLACK, WEBHOOK }

    public enum Event { TICKET_OPENED, TENANT_REPLIED, TICKET_RESOLVED }

    /** Un canal prêt à l'envoi : la cible est en clair ici, et seulement ici. */
    public record Channel(String id, Type type, String label, String target,
                          boolean enabled, Set<Event> events) {
        public boolean wants(Event event) {
            return enabled && target != null && !target.isBlank() && events.contains(event);
        }
    }

    /** La partie non secrète d'un canal, telle qu'elle dort dans la section. */
    record Stored(String id, String type, String label, boolean enabled, List<String> events) {}

    @Inject PlatformSettingsService settings;
    @Inject Logger log;

    private final ObjectMapper json = new ObjectMapper();

    public List<Channel> list() {
        Map<String, String> values = settings.readFromDb(SECTION);
        if (values.isEmpty()) return legacy();
        List<Channel> out = new ArrayList<>();
        for (Stored s : stored(values)) {
            Type type = typeOf(s.type());
            if (type == null) continue;
            out.add(new Channel(s.id(), type, s.label(),
                    values.get(TARGET_PREFIX + s.id()), s.enabled(), eventsOf(s.events())));
        }
        return out;
    }

    public List<Channel> enabledFor(Event event) {
        return list().stream().filter(c -> c.wants(event)).toList();
    }

    public EditorAlertsDto view() {
        var raw = settings.rawValuesForAdmin(SECTION);
        List<EditorAlertsDto.Channel> channels = new ArrayList<>();
        for (Channel c : list()) {
            boolean secret = c.type() != Type.EMAIL;
            channels.add(new EditorAlertsDto.Channel(
                    c.id(), c.type().name(), c.label(),
                    secret ? null : c.target(),
                    c.target() != null && !c.target().isBlank(),
                    c.enabled(),
                    c.events().stream().map(Enum::name).sorted().toList()));
        }
        return new EditorAlertsDto(channels,
                raw.updatedAt() != null ? raw.updatedAt().toString() : null, raw.updatedBy());
    }

    /**
     * Remplace la liste. Une cible vide sur un webhook garde celle déjà
     * enregistrée ; sur un canal hérité jamais enregistré, celle des
     * anciens champs.
     */
    public void save(EditorAlertsUpsertDto payload, String updatedBy) {
        Map<String, String> current = settings.readFromDb(SECTION);
        Map<String, String> legacyTargets = new HashMap<>();
        for (Channel c : legacy()) legacyTargets.put(c.id(), c.target());

        Map<String, String> values = new LinkedHashMap<>();
        Set<String> secrets = new HashSet<>();
        List<Stored> stored = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (EditorAlertsUpsertDto.Channel in : payload.channels()) {
            Type type = typeOf(in.type());
            if (type == null) throw new BusinessException(Messages.msg("m.alr-type-unknown", in.type()));
            String id = in.id() == null || in.id().isBlank()
                    ? UUID.randomUUID().toString().substring(0, 8) : in.id().trim();
            if (!seen.add(id)) continue;
            String target = in.target() == null ? "" : in.target().trim();
            String key = TARGET_PREFIX + id;
            if (type == Type.EMAIL) {
                if (target.isBlank()) throw new BusinessException(Messages.msg("m.alr-target-required", labelOf(in)));
                values.put(key, target);
            } else {
                secrets.add(key);
                if (target.isBlank()) {
                    // Rien saisi : on garde ce qu'on a, ou ce que les anciens champs portaient.
                    String kept = current.get(key);
                    if (kept == null || kept.isBlank()) kept = legacyTargets.get(id);
                    if (kept == null || kept.isBlank()) {
                        throw new BusinessException(Messages.msg("m.alr-target-required", labelOf(in)));
                    }
                    target = kept;
                } else if (!target.startsWith("https://") && !target.startsWith("http://")) {
                    throw new BusinessException(Messages.msg("m.alr-url-invalid", labelOf(in)));
                }
                values.put(key, target);
            }
            List<String> events = in.events() == null ? List.of() : in.events().stream()
                    .map(e -> e == null ? "" : e.trim().toUpperCase(Locale.ROOT))
                    .filter(e -> eventOf(e) != null).distinct().toList();
            stored.add(new Stored(id, type.name(), in.label() == null ? "" : in.label().trim(),
                    in.enabled(), events));
        }
        try {
            values.put(CHANNELS_KEY, json.writeValueAsString(stored));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        // Les cibles secrètes passent en clair au service, qui les chiffre ;
        // une cible reprise de l'existant repasse par le même chemin.
        settings.writeSection(SECTION, values, secrets, updatedBy);
    }

    // ─── Lecture ───

    private List<Stored> stored(Map<String, String> values) {
        String raw = values.get(CHANNELS_KEY);
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return json.readValue(raw, new TypeReference<List<Stored>>() {});
        } catch (Exception e) {
            log.warnf("Canaux d'alerte illisibles, liste ignorée : %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * Ce que les anciens champs disaient : la boîte d'assistance, à défaut
     * l'adresse d'expédition, puis le webhook Slack. Tous deux abonnés à
     * tout, comme ils l'étaient.
     */
    private List<Channel> legacy() {
        Map<String, String> email = settings.readFromDb("email");
        List<Channel> out = new ArrayList<>();
        String inbox = firstNonBlank(email.get("supportInbox"), email.get("from"));
        if (inbox != null) {
            out.add(new Channel("legacy-email", Type.EMAIL, "Boîte d'assistance", inbox.trim(),
                    true, Set.of(Event.TICKET_OPENED, Event.TENANT_REPLIED)));
        }
        String slack = email.get("supportSlackWebhook");
        if (slack != null && !slack.isBlank()) {
            out.add(new Channel("legacy-slack", Type.SLACK, "Slack", slack.trim(),
                    true, Set.of(Event.TICKET_OPENED, Event.TENANT_REPLIED)));
        }
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String labelOf(EditorAlertsUpsertDto.Channel c) {
        return c.label() == null || c.label().isBlank() ? c.type() : c.label();
    }

    static Type typeOf(String s) {
        if (s == null) return null;
        try { return Type.valueOf(s.trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
    }

    static Event eventOf(String s) {
        if (s == null) return null;
        try { return Event.valueOf(s.trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
    }

    private static Set<Event> eventsOf(List<String> names) {
        Set<Event> out = new HashSet<>();
        if (names != null) for (String n : names) { Event e = eventOf(n); if (e != null) out.add(e); }
        return out;
    }
}
