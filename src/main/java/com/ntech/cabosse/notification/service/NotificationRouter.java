package com.ntech.cabosse.notification.service;

import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationUsage;
import com.ntech.cabosse.shared.i18n.Locales;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Applique la règle du tenant à un événement : qui reçoit, par où, avec
 * quelles copies (CE-205).
 *
 * <p>Les notifieurs décrivent l'événement et son audience par défaut ;
 * le routeur applique l'exception posée par l'administrateur : profils
 * destinataires à la place des porteurs du droit, canaux choisis (au
 * moins un), copies courriel. Les exclusions de gouvernance (le déposant
 * n'approuve pas, l'approbateur ne décaisse pas) restent appliquées
 * quelle que soit la règle : elles sont dans l'audience passée et dans
 * {@code excludedUserIds}, la configuration ne les lève pas.</p>
 */
@ApplicationScoped
public class NotificationRouter {

    /** Au-delà, on cesse de parcourir : une structure n'a pas mille comptes. */
    private static final int MAX_USERS = 500;

    @Inject NotificationQueue queue;
    @Inject NotificationRuleService rules;
    @Inject UserRepository users;
    @Inject TenantPreferencesLookup preferences;
    @Inject com.ntech.cabosse.shared.tenant.TenantContext tenantContext;

    /**
     * Route un événement vers ses destinataires.
     *
     * @param eventCode       code du catalogue
     * @param defaultRecipients audience par défaut, exclusions déjà faites
     * @param excludedUserIds exclusions de gouvernance, réappliquées si la
     *                        règle remplace l'audience par des profils
     * @param subjectRef      référence métier (reçu, avance…)
     * @param subject         sujet dans la langue du destinataire
     * @param body            corps dans la langue du destinataire
     */
    public void route(String eventCode,
                      List<UserEntity> defaultRecipients,
                      List<UUID> excludedUserIds,
                      String subjectRef,
                      Function<Locale, String> subject,
                      Function<Locale, String> body) {
        NotificationRuleResolution rule = rules.resolution(eventCode);
        if (!rule.enabled()) return;

        String fallback = preferences.current().language;
        List<UserEntity> recipients = rule.recipientRoleIds() == null
                ? defaultRecipients
                : usersOfRoles(rule.recipientRoleIds(), excludedUserIds);

        for (UserEntity user : recipients) {
            Locale locale = Locales.firstOf(user.locale, fallback);
            String renderedSubject = subject.apply(locale);
            String renderedBody = body.apply(locale);
            if (rule.channels().contains(NotificationChannel.EMAIL)
                    && user.email != null && !user.email.isBlank()) {
                queue.enqueue(new NotificationQueue.Request(
                        NotificationChannel.EMAIL, NotificationUsage.ALERT,
                        user.email, renderedSubject, renderedBody,
                        eventCode, subjectRef, Locales.tag(locale), null));
            }
            if (rule.channels().contains(NotificationChannel.IN_APP)) {
                queue.enqueue(NotificationQueue.Request.inApp(
                        user.id, renderedSubject, renderedBody,
                        eventCode, subjectRef, locale));
            }
            if (rule.channels().contains(NotificationChannel.SMS)
                    && user.phone != null && !user.phone.isBlank()) {
                queue.enqueue(NotificationQueue.Request.sms(
                        user.phone, renderedBody,
                        eventCode, NotificationUsage.ALERT, locale));
            }
        }

        // Les copies : des adresses, pas des comptes. Toujours par
        // courriel, dans la langue de la structure.
        Locale structureLocale = Locales.of(fallback);
        for (String cc : rule.ccEmails()) {
            queue.enqueue(new NotificationQueue.Request(
                    NotificationChannel.EMAIL, NotificationUsage.ALERT,
                    cc, subject.apply(structureLocale), body.apply(structureLocale),
                    eventCode, subjectRef, Locales.tag(structureLocale), null));
        }
    }

    /** Les comptes actifs des profils retenus, exclusions de gouvernance comprises. */
    private List<UserEntity> usersOfRoles(List<UUID> roleIds, List<UUID> excludedUserIds) {
        List<UserEntity> out = new ArrayList<>();
        UUID tenantId = tenantContext.tenantId();
        if (tenantId == null) return out;
        for (UserEntity user : users.findByTenant(tenantId, 0, MAX_USERS)) {
            if (user.status != UserStatus.ACTIVE) continue;
            if (excludedUserIds != null && excludedUserIds.contains(user.id)) continue;
            if (user.tenantRoleIds == null) continue;
            boolean member = roleIds.stream().anyMatch(user.tenantRoleIds::contains);
            if (member) out.add(user);
        }
        return out;
    }
}
