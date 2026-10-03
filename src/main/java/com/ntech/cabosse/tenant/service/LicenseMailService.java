package com.ntech.cabosse.tenant.service;

import com.ntech.cabosse.settings.mail.PlatformMailerService;
import com.ntech.cabosse.shared.i18n.Locales;
import com.ntech.cabosse.shared.i18n.MailTexts;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.tenant.entity.BillingCycle;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantSubscription;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.repository.UserRepository;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * La confirmation d'activation de licence, envoyée aux adresses choisies.
 *
 * <p>L'éditeur active la licence d'une structure depuis sa console et
 * désigne, parmi les comptes de cette structure, qui doit en être informé
 * (demandé le 03/10/2026). Tout le monde n'a pas à recevoir un courrier
 * qui parle de facturation.</p>
 *
 * <p>Un envoi par destinataire plutôt qu'une liste en copie : chacun
 * reçoit le courrier dans sa propre langue, et aucun ne découvre les
 * adresses des autres.</p>
 *
 * <p>L'envoi est accessoire à l'activation. Une messagerie qui ne répond
 * pas ne doit pas défaire une licence déjà accordée : les échecs sont
 * consignés, jamais propagés.</p>
 */
@ApplicationScoped
public class LicenseMailService {

    @Inject PlatformMailerService mailer;
    @Inject UserRepository users;
    @Inject Logger log;

    @Inject
    @Location("mail/license-activation.html")
    Template licenseTemplate;

    /** Signataire du courrier. Il engage l'éditeur, pas la plateforme. */
    @ConfigProperty(name = "cabosse.license.sender-name", defaultValue = "NEIBA Technologies")
    String senderName;

    @ConfigProperty(name = "cabosse.license.sender-role", defaultValue = "NEIBA Technologies")
    String senderRole;

    /**
     * Envoie la confirmation aux adresses demandées.
     *
     * @param tenant    la structure dont la licence vient d'être activée
     * @param recipients adresses retenues ; une adresse étrangère à la
     *                   structure est ignorée plutôt que servie
     * @return le nombre de courriers réellement partis
     */
    public int sendActivation(TenantEntity tenant, List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) return 0;
        TenantSubscription sub = tenant.subscription;
        if (sub == null) return 0;

        // Les comptes de la structure font foi : une adresse saisie
        // ailleurs n'a pas à recevoir sa facturation.
        List<UserEntity> known = users.find("tenantId", tenant.id).list();
        int sent = 0;
        for (String address : recipients) {
            Optional<UserEntity> user = known.stream()
                    .filter(u -> u.email != null && u.email.equalsIgnoreCase(address.trim()))
                    .findFirst();
            if (user.isEmpty()) {
                log.warnf("Adresse %s étrangère au tenant %s : courrier non envoyé",
                        address, tenant.slug);
                continue;
            }
            if (sendTo(tenant, sub, user.get())) sent++;
        }
        return sent;
    }

    private boolean sendTo(TenantEntity tenant, TenantSubscription sub, UserEntity user) {
        try {
            Locale locale = Locales.firstOf(
                    user.locale,
                    tenant.preferences != null ? tenant.preferences.language : null);

            MailTexts texts = MailTexts.in(locale)
                    .put("title", "m.mail-license-title")
                    .put("heading", "m.mail-license-heading")
                    .put("periodLabel", "m.mail-license-period-label")
                    .put("greeting", "m.mail-license-greeting")
                    .put("intro", "m.mail-license-intro", tenant.name)
                    .put("licenseInfo", "m.mail-license-info")
                    .put("fieldOrganization", "m.mail-license-field-organization")
                    .put("fieldSolution", "m.mail-license-field-solution")
                    .put("fieldPeriod", "m.mail-license-field-period")
                    .put("fieldState", "m.mail-license-field-state")
                    .put("stateActive", "m.mail-license-state-active")
                    .put("instanceTitle", "m.mail-license-instance")
                    .put("hostingTitle", "m.mail-license-hosting-title")
                    .put("hostingBody", "m.mail-license-hosting-body")
                    .put("dataTitle", "m.mail-license-data-title")
                    .put("dataBody", "m.mail-license-data-body")
                    .put("backupTitle", "m.mail-license-backup-title")
                    .put("backupBody", "m.mail-license-backup-body")
                    .put("maintenanceTitle", "m.mail-license-maintenance-title")
                    .put("maintenanceBody", "m.mail-license-maintenance-body")
                    .put("invoiceNote", "m.mail-license-invoice-note")
                    .put("closing", "m.mail-license-closing")
                    .put("signOff", "m.mail-license-signoff");

            String html = licenseTemplate
                    .data("tenantName", tenant.name)
                    .data("periodLong", periodLong(sub, locale))
                    .data("periodShort", periodShort(sub, locale))
                    .data("licenseLabel", sub.label)
                    .data("durationLabel", durationLabel(sub, locale))
                    .data("amount", money(sub.amount, tenant, locale))
                    .data("senderName", senderName)
                    .data("senderRole", senderRole)
                    .data("t", texts.build())
                    .render();

            mailer.sendHtml(user.email, Messages.msg(locale, "m.mail-license-title"), html);
            return true;
        } catch (Exception e) {
            // Hors requête et accessoire : l'activation est acquise, le
            // courrier se renvoie depuis la console.
            log.errorf(e, "Échec envoi de la licence à %s (tenant %s)", user.email, tenant.slug);
            return false;
        }
    }

    /** « 1er octobre 2026 au 30 septembre 2027 », dans la langue du lecteur. */
    private static String periodLong(TenantSubscription sub, Locale locale) {
        DateTimeFormatter f = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale);
        return sub.startDate.format(f) + " — " + sub.endDate.format(f);
    }

    private static String periodShort(TenantSubscription sub, Locale locale) {
        DateTimeFormatter f = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale);
        return sub.startDate.format(f) + " — " + sub.endDate.format(f);
    }

    private static String durationLabel(TenantSubscription sub, Locale locale) {
        int months = sub.cycle == BillingCycle.YEARLY ? sub.periods * 12 : sub.periods;
        return sub.cycle == BillingCycle.YEARLY
                ? Messages.msg(locale, "m.mail-license-duration-years", sub.periods)
                : Messages.msg(locale, "m.mail-license-duration-months", months);
    }

    /**
     * Le montant dans la devise de la structure.
     *
     * <p>Vide quand aucun montant n'a été arrêté : un zéro se lirait
     * comme une licence gratuite, et le gabarit masque alors tout le bloc
     * de facturation.</p>
     */
    private static String money(BigDecimal amount, TenantEntity tenant, Locale locale) {
        if (amount == null) return null;
        String currency = tenant.preferences != null && tenant.preferences.currency != null
                ? tenant.preferences.currency : "XOF";
        NumberFormat f = NumberFormat.getNumberInstance(locale);
        f.setMaximumFractionDigits(0);
        return f.format(amount) + " " + currency;
    }
}
