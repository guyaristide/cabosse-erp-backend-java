package com.ntech.cabosse.members.service;

import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberIdentityDocument;
import com.ntech.cabosse.shared.exception.BusinessException;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Vigilance sur les paiements aux producteurs (backlog MEM-12).
 *
 * <p>Deux contrôles, actifs seulement si le tenant a activé la préférence
 * {@code requireProducerPaymentVigilance} :</p>
 * <ol>
 *   <li>une pièce d'identité <strong>scannée</strong> doit être au dossier
 *       — un numéro saisi sans justificatif ne prouve rien ;</li>
 *   <li>un versement mobile money vers un compte dont le titulaire n'est
 *       pas le producteur exige un mandat écrit au dossier.</li>
 * </ol>
 *
 * <p>Ce n'est pas un dispositif réglementaire : c'est le contrôle qui
 * protège l'argent de la structure quand la collecte passe par des
 * délégués et des paiements à distance.</p>
 */
public final class MemberPaymentVigilance {

    private MemberPaymentVigilance() {}

    /** Modes de règlement qui sortent de l'argent vers un compte distant. */
    public static boolean isMobileMoney(String paymentMethod) {
        if (paymentMethod == null) return false;
        String normalized = normalize(paymentMethod);
        return normalized.contains("mobile") || normalized.contains("money");
    }

    /**
     * @param member        producteur payé
     * @param paymentMethod mode de règlement de l'opération
     */
    public static void check(MemberEntity member, String paymentMethod,
                             java.util.Set<String> identityProofTypes) {
        if (!hasScannedIdentityDocument(member, identityProofTypes)) {
            throw new BusinessException(
                    "Vigilance paiements : aucune pièce d'identité scannée au dossier de « "
                            + member.name + " ». Joindre le scan avant de payer.");
        }
        if (isMobileMoney(paymentMethod) && paysAThirdParty(member)
                && !member.mobileMoneyMandateOnFile) {
            throw new BusinessException(
                    "Vigilance paiements : le compte mobile money de « " + member.name
                            + " » est au nom de « " + member.mobileMoneyHolderName
                            + " ». Un mandat écrit au dossier est requis.");
        }
    }

    private static boolean hasScannedIdentityDocument(MemberEntity m,
                                                     java.util.Set<String> identityProofTypes) {
        if (m.identityDocuments != null && m.identityDocuments.stream()
                .anyMatch(d -> isScanned(d) && proves(d.type, identityProofTypes))) {
            return true;
        }
        return m.idCardFileId != null;
    }

    /** {@code null} : pas de référentiel, toute pièce scannée compte (voir complétude). */
    private static boolean proves(String type, java.util.Set<String> identityProofTypes) {
        if (identityProofTypes == null) return true;
        return type != null
                && identityProofTypes.contains(type.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean isScanned(MemberIdentityDocument d) {
        return d != null && d.fileId != null;
    }

    /** Titulaire renseigné et distinct du producteur : le paiement sort du foyer. */
    private static boolean paysAThirdParty(MemberEntity m) {
        String holder = m.mobileMoneyHolderName;
        if (holder == null || holder.isBlank()) return false;
        return !normalize(holder).equals(normalize(m.name));
    }

    /** Comparaison indulgente : accents, casse et espaces multiples ne comptent pas. */
    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
