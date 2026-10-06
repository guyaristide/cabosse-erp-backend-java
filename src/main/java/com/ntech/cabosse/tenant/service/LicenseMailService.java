package com.ntech.cabosse.tenant.service;

import com.ntech.cabosse.settings.mail.MailFile;
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
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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
    @Inject com.ntech.cabosse.tenant.repository.TenantRepository tenants;
    @Inject Logger log;

    @Inject
    @Location("mail/license-activation.html")
    Template licenseTemplate;

    /** Signataire du courrier. Il engage l'éditeur, pas la plateforme. */
    /** Contrôle de forme d'une adresse saisie à la main, rien de plus. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    @ConfigProperty(name = "cabosse.license.sender-name", defaultValue = "NEIBA Technologies")
    String senderName;

    /**
     * Seconde ligne de la signature.
     *
     * <p>Elle portait le même nom que la première, et la signature
     * répétait l'éditeur deux fois (signalé le 05/10/2026). C'est le
     * produit qui la porte désormais.</p>
     */
    @ConfigProperty(name = "cabosse.license.sender-role", defaultValue = "Cabosse ERP")
    String senderRole;

    /**
     * Envoie la confirmation aux adresses demandées.
     *
     * @param tenant     la structure dont la licence vient d'être activée
     * @param recipients adresses cochées parmi les comptes de la
     *                   structure ; une adresse qui n'en est pas se
     *                   serait glissée par erreur, elle est écartée
     * @param extraEmails adresses saisies à la main par l'éditeur : un
     *                   comptable externe ou un directeur financier n'a
     *                   pas toujours de compte, et le courrier doit
     *                   quand même pouvoir l'atteindre (05/10/2026)
     * @param invoice    la facture, jointe au courrier qui l'annonce
     * @return le nombre de courriers réellement partis
     */
    public int sendActivation(TenantEntity tenant, List<String> recipients,
                              List<String> extraEmails, MailFile invoice) {
        TenantSubscription sub = tenant.subscription;
        if (sub == null) return 0;
        List<MailFile> files = invoice == null || invoice.isEmpty()
                ? List.of() : List.of(invoice);

        // Les comptes de la structure font foi pour les adresses
        // cochées : l'écran les propose, une inconnue n'y vient que par
        // accident.
        List<UserEntity> known = users.find("tenantId", tenant.id).list();
        int sent = 0;
        Set<String> served = new LinkedHashSet<>();
        for (String address : recipients == null ? List.<String>of() : recipients) {
            Optional<UserEntity> user = known.stream()
                    .filter(u -> u.email != null && u.email.equalsIgnoreCase(address.trim()))
                    .findFirst();
            if (user.isEmpty()) {
                log.warnf("Adresse %s étrangère au tenant %s : courrier non envoyé",
                        address, tenant.slug);
                continue;
            }
            if (served.add(user.get().email.toLowerCase(Locale.ROOT))
                    && sendTo(tenant, sub, user.get().email, user.get().locale, files)) {
                sent++;
            }
        }

        // Les adresses libres partent telles quelles : c'est l'éditeur
        // qui les saisit, sur son propre courrier commercial, et rien ne
        // dit que le destinataire doit être un utilisateur du logiciel.
        for (String address : extraEmails == null ? List.<String>of() : extraEmails) {
            String clean = address == null ? null : address.trim();
            if (clean == null || !EMAIL.matcher(clean).matches()) {
                log.warnf("Adresse « %s » illisible : courrier non envoyé", address);
                continue;
            }
            if (served.add(clean.toLowerCase(Locale.ROOT))
                    && sendTo(tenant, sub, clean, null, files)) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * Renvoie la confirmation d'une licence déjà activée.
     *
     * <p>Un courrier se perd, une adresse change, un interlocuteur
     * arrive après coup : le renvoyer ne doit pas obliger à réactiver la
     * licence (demandé le 04/10/2026).</p>
     *
     * @return le nombre de courriers réellement partis
     */
    public int resend(java.util.UUID tenantId, List<String> recipients,
                      List<String> extraEmails, MailFile invoice) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new com.ntech.cabosse.shared.exception.NotFoundException(
                    Messages.msg("m.tnt-not-found-2", tenantId));
        }
        if (tenant.subscription == null) {
            throw new com.ntech.cabosse.shared.exception.BusinessException(
                    Messages.msg("m.tnt-no-license-to-resend", tenant.name));
        }
        return sendActivation(tenant, recipients, extraEmails, invoice);
    }

    private boolean sendTo(TenantEntity tenant, TenantSubscription sub, String address,
                           String preferredLocale, List<MailFile> files) {
        try {
            Locale locale = Locales.firstOf(
                    preferredLocale,
                    tenant.preferences != null ? tenant.preferences.language : null);

            // Lettre signée : pas de pied de page, il répétait la marque
            // deux lignes sous le signataire (signalé le 06/10/2026).
            MailTexts texts = MailTexts.in(locale)
                    .withoutFooter()
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

            mailer.sendHtml(address, Messages.msg(locale, "m.mail-license-title"), html, files);
            return true;
        } catch (Exception e) {
            // Hors requête et accessoire : l'activation est acquise, le
            // courrier se renvoie depuis la console.
            log.errorf(e, "Échec envoi de la licence à %s (tenant %s)", address, tenant.slug);
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
