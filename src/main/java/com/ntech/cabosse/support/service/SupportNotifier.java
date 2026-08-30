package com.ntech.cabosse.support.service;

import com.ntech.cabosse.settings.mail.PlatformMailerService;
import com.ntech.cabosse.settings.service.PlatformSettingsService;
import com.ntech.cabosse.shared.config.ApplicationConfig;
import com.ntech.cabosse.shared.i18n.Locales;
import com.ntech.cabosse.shared.i18n.MailTexts;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.support.entity.SupportTicketEntity;
import com.ntech.cabosse.support.entity.TicketMessageEntity;
import com.ntech.cabosse.support.entity.TicketStatus;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Locale;
import java.util.Map;

/**
 * Les avis par courriel autour d'un ticket.
 *
 * <p>Un ticket ne vaut que si quelqu'un apprend qu'il existe. Sans avis,
 * l'éditeur découvre la panne en ouvrant son back-office, et la structure
 * découvre la réponse en repassant sur l'écran — c'est-à-dire trop tard
 * dans les deux sens.</p>
 *
 * <p>Le socle de notifications n'est pas employé ici : sa file est
 * <strong>par structure</strong>, or la moitié de ces messages s'adresse à
 * l'éditeur, qui n'a pas de file. Les faire tous passer par
 * l'expéditeur direct garde un seul chemin et un seul comportement.
 * L'envoi est au mieux : un SMTP muet ne doit pas empêcher d'ouvrir un
 * ticket, sinon la panne qu'on venait signaler bloque le signalement.</p>
 */
@ApplicationScoped
public class SupportNotifier {

    @Inject PlatformMailerService mailer;
    @Inject PlatformSettingsService settings;
    @Inject ApplicationConfig appConfig;
    @Inject TenantRepository tenants;
    @Inject Logger log;

    @Inject
    @Location("mail/support-ticket.html")
    Template template;

    // ─── Vers l'éditeur ───

    public void ticketOpened(SupportTicketEntity t) {
        String inbox = editorInbox();
        if (inbox == null) return;
        Locale locale = Locale.FRENCH;
        send(inbox, locale,
                Messages.msg(locale, "m.mail-ticket-opened-subject", t.ref, t.tenantName),
                MailTexts.in(locale)
                        .put("title", "m.mail-ticket-opened-title")
                        .put("heading", "m.mail-ticket-opened-heading")
                        .put("intro", "m.mail-ticket-opened-intro", t.reportedBy, t.tenantName)
                        .put("cta", "m.mail-ticket-cta-staff")
                        .put("fallbackHint", "m.mail-fallback-hint")
                        .put("noReply", "m.mail-ticket-no-reply"),
                t, t.subject, t.description, staffUrl(t));
    }

    public void tenantReplied(SupportTicketEntity t, TicketMessageEntity m) {
        String inbox = editorInbox();
        if (inbox == null) return;
        Locale locale = Locale.FRENCH;
        send(inbox, locale,
                Messages.msg(locale, "m.mail-ticket-reply-subject", t.ref, t.subject),
                MailTexts.in(locale)
                        .put("title", "m.mail-ticket-reply-title")
                        .put("heading", "m.mail-ticket-reply-heading")
                        .put("intro", "m.mail-ticket-reply-intro-staff", m.authorName, t.tenantName)
                        .put("cta", "m.mail-ticket-cta-staff")
                        .put("fallbackHint", "m.mail-fallback-hint")
                        .put("noReply", "m.mail-ticket-no-reply"),
                t, t.subject, m.body, staffUrl(t));
    }

    // ─── Vers la structure ───

    public void staffReplied(SupportTicketEntity t, TicketMessageEntity m) {
        // Une note interne ne sort jamais : le garde est déjà posé chez
        // l'appelant, on le redouble ici parce qu'un courriel parti ne se
        // rattrape pas.
        if (m.internal || t.reportedByEmail == null) return;
        Locale locale = tenantLocale(t);
        send(t.reportedByEmail, locale,
                Messages.msg(locale, "m.mail-ticket-reply-subject", t.ref, t.subject),
                MailTexts.in(locale)
                        .put("title", "m.mail-ticket-reply-title")
                        .put("heading", "m.mail-ticket-reply-heading")
                        .put("intro", "m.mail-ticket-reply-intro-tenant", t.ref)
                        .put("cta", "m.mail-ticket-cta-tenant")
                        .put("fallbackHint", "m.mail-fallback-hint")
                        .put("noReply", "m.mail-ticket-no-reply"),
                t, t.subject, m.body, tenantUrl(t));
    }

    /**
     * Un ticket résolu ou clos se dit.
     *
     * <p>Les états intermédiaires ne s'annoncent pas : « pris en charge »
     * puis « en attente » puis « repris » remplirait une boîte aux lettres
     * sans rien apprendre. Seul l'aboutissement mérite un courriel, parce
     * qu'il appelle une vérification côté structure.</p>
     */
    public void statusChanged(SupportTicketEntity t, TicketStatus previous) {
        if (t.reportedByEmail == null) return;
        if (t.status != TicketStatus.RESOLVED && t.status != TicketStatus.CLOSED) return;
        Locale locale = tenantLocale(t);
        String statusLabel = Messages.msg(locale, t.status.messageKey());
        send(t.reportedByEmail, locale,
                Messages.msg(locale, "m.mail-ticket-status-subject", t.ref, statusLabel),
                MailTexts.in(locale)
                        .put("title", "m.mail-ticket-status-title")
                        .put("heading", "m.mail-ticket-status-heading")
                        .put("intro", "m.mail-ticket-status-intro", t.ref, statusLabel)
                        .put("cta", "m.mail-ticket-cta-tenant")
                        .put("fallbackHint", "m.mail-fallback-hint")
                        .put("noReply", "m.mail-ticket-no-reply"),
                t, t.subject, lastPublicBody(t), tenantUrl(t));
    }

    // ─── Helpers ───

    private void send(String to, Locale locale, String subject, MailTexts texts,
                      SupportTicketEntity t, String ticketSubject, String body, String url) {
        try {
            String html = template
                    .data("ticketRef", t.ref)
                    .data("subject", ticketSubject)
                    .data("body", body == null ? "" : body)
                    .data("ticketUrl", url)
                    .data("t", texts.build())
                    .render();
            mailer.sendHtml(to, subject, html);
        } catch (Exception e) {
            // Le ticket compte plus que son avis : on trace et on continue.
            log.errorf(e, "Avis de ticket non envoyé (ref=%s, to=%s)", t.ref, to);
        }
    }

    /**
     * La boîte de l'éditeur.
     *
     * <p>À défaut d'adresse dédiée, l'adresse d'expédition configurée fait
     * l'affaire : c'est la boîte de l'éditeur, et un avis qui revient à
     * l'expéditeur vaut mieux qu'un avis qui n'existe pas.</p>
     */
    private String editorInbox() {
        Map<String, String> email = settings.readFromDb("email");
        String inbox = email.get("supportInbox");
        if (inbox == null || inbox.isBlank()) inbox = email.get("from");
        if (inbox == null || inbox.isBlank()) {
            log.warnf("Aucune adresse d'assistance configurée : avis de ticket non envoyé");
            return null;
        }
        return inbox.trim();
    }

    private Locale tenantLocale(SupportTicketEntity t) {
        TenantEntity tenant = tenants.findById(t.tenantId);
        return Locales.firstOf(tenant != null && tenant.preferences != null
                ? tenant.preferences.language : null);
    }

    private String staffUrl(SupportTicketEntity t) {
        return "%s/backoffice/support/%s".formatted(appConfig.frontendBaseUrl(), t.id);
    }

    private String tenantUrl(SupportTicketEntity t) {
        return "%s/app/assistance/%s".formatted(appConfig.frontendBaseUrl(), t.id);
    }

    private static String lastPublicBody(SupportTicketEntity t) {
        for (int i = t.messages.size() - 1; i >= 0; i--) {
            TicketMessageEntity m = t.messages.get(i);
            if (!m.internal) return m.body;
        }
        return t.description;
    }
}
