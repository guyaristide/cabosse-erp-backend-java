package com.ntech.cabosse.members.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelStatus;
import com.ntech.cabosse.agriculture.parcel.repository.ParcelRepository;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignService;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.crop.service.CropService;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberExternalCode;
import com.ntech.cabosse.members.entity.MemberGender;
import com.ntech.cabosse.members.entity.MemberHousehold;
import com.ntech.cabosse.members.entity.MemberIdentityDocument;
import com.ntech.cabosse.members.entity.MemberMaritalStatus;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Fiche signalétique du producteur membre (backlog MEM-10).
 *
 * <p>Reproduit le formulaire papier fourni par le client : bandeaux de
 * section, champs <strong>numérotés de 1 à 50</strong>, deux colonnes
 * encadrées par bloc, quatre emplacements de cultures secondaires teintés.
 * La numérotation n'est pas décorative : les agents de terrain s'y réfèrent
 * pour dicter et vérifier une donnée, elle doit rester stable même quand un
 * champ est vide.</p>
 *
 * <p>Les mentions propres à un organisme de filière sont formulées de
 * manière neutre (« carte producteur » plutôt que le nom d'un conseil
 * national) : la plateforme sert plusieurs filières et plusieurs pays.</p>
 */
@ApplicationScoped
public class MemberProfileSheetService {

    /** Emplacements de cultures secondaires imprimés, comme sur le modèle. */
    private static final int SECONDARY_CROP_SLOTS = 4;

    private static final DateTimeFormatter FR_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);

    private static final Color BAND = new Color(0x1F, 0x5C, 0x5C);
    private static final Color BOX_BORDER = new Color(0x22, 0x22, 0x22);
    private static final Color HIGHLIGHT = new Color(0xFB, 0xE0, 0xD0);
    private static final Color VALUE_GREY = new Color(0x59, 0x59, 0x59);
    private static final Color[] CROP_TINTS = {
            new Color(0xDD, 0xEB, 0xF7), // bleu
            new Color(0xFC, 0xE4, 0xD6), // pêche
            new Color(0xE2, 0xEF, 0xDA), // vert
            new Color(0xEA, 0xD1, 0xDC), // mauve
    };

    private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, Color.BLACK);
    private static final Font VALUE = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, VALUE_GREY);
    private static final Font VALUE_STRONG = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8f, Color.BLACK);
    private static final Font BAND_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, Color.WHITE);
    private static final Font MATRICULE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13f, Color.BLACK);

    @Inject MemberRepository members;
    @Inject ParcelRepository parcels;
    @Inject SectionRepository sections;
    @Inject CampaignService campaigns;
    @Inject CropService crops;
    @Inject TenantRepository tenants;
    @Inject TenantContext tenantContext;
    @Inject ProducerRefKeyService producerRefKeys;
    @Inject com.ntech.cabosse.tenant.service.TenantPreferencesLookup preferences;

    public byte[] build(UUID memberId, UUID campaignId) {
        MemberEntity m = members.findById(memberId)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.mbr-member-not-found", memberId)));

        TenantEntity tenant = tenants.findById(tenantContext.tenantId());
        String organization = tenant != null ? tenant.name : "";
        CampaignEntity campaign = campaignId != null ? campaigns.get(campaignId) : campaigns.current();

        String sectionName = m.sectionId == null ? null
                : sections.findById(m.sectionId).map(s -> s.name).orElse(null);
        String agentName = m.followUpAgentMemberId == null ? null
                : members.findById(m.followUpAgentMemberId).map(a -> a.name).orElse(null);

        List<ParcelEntity> memberParcels = parcels.listByMember(m.id).stream()
                .filter(p -> p.status != ParcelStatus.ABANDONED)
                .sorted(mainCropFirst())
                .toList();
        Map<String, String> cropNames = crops.namesByCode();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 30, 30, 26, 26);
            PdfWriter.getInstance(doc, out)
                    .setPageEvent(new com.ntech.cabosse.shared.export.PdfBranding());
            doc.open();

            doc.add(titleBand(organization));
            doc.add(header(m, campaign, sectionName, agentName));

            doc.add(band("DONNÉES DE BASE SUR LE PRODUCTEUR MEMBRE DE LA COOPÉRATIVE"));
            doc.add(boxed(baseGrid(m)));

            doc.add(band("SITUATION FAMILIALE"));
            doc.add(boxed(householdGrid(m.household)));

            doc.add(band("CULTURE PRINCIPALE"));
            doc.add(boxed(mainCropGrid(memberParcels.isEmpty() ? null : memberParcels.get(0),
                    cropNames, campaign)));

            doc.add(band("CULTURES SECONDAIRES"));
            doc.add(boxed(secondaryCropsGrid(memberParcels, cropNames, campaign)));

            doc.add(band("INFORMATIONS ADDITIONNELLES"));
            doc.add(boxed(additionalGrid(m)));

            int extra = Math.max(0, memberParcels.size() - 1 - SECONDARY_CROP_SLOTS);
            if (extra > 0) {
                Paragraph note = new Paragraph(
                        extra + " culture(s) secondaire(s) supplémentaire(s) non imprimée(s), "
                                + "consultables sur la fiche du producteur.",
                        FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 7f, VALUE_GREY));
                note.setSpacingBefore(6);
                doc.add(note);
            }

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException(Messages.msg("m.mbr-sheet-generation-failed", e.getMessage()));
        }
    }

    /** Parcelle marquée principale d'abord, sinon la plus grande superficie. */
    private static Comparator<ParcelEntity> mainCropFirst() {
        return Comparator.comparing((ParcelEntity p) -> !p.mainCrop)
                .thenComparing(p -> p.surfaceHa == null ? BigDecimal.ZERO : p.surfaceHa,
                        Comparator.reverseOrder());
    }

    // ─── Structure de la page ───────────────────────────────────────

    private static PdfPTable titleBand(String organization) {
        String title = ("FICHE SIGNALÉTIQUE PRODUCTEUR MEMBRE " + organization)
                .toUpperCase(Locale.FRANCE);
        PdfPTable t = fullWidth(1);
        PdfPCell cell = new PdfPCell(new Phrase(title, BAND_FONT));
        cell.setBackgroundColor(BAND);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(5);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        t.addCell(cell);
        t.setSpacingAfter(4);
        return t;
    }

    /**
     * En-tête du modèle : section et matricule à gauche, campagne, agent de
     * suivi et date de mise à jour à droite, valeurs sur fond teinté.
     */
    private PdfPTable header(MemberEntity m, CampaignEntity campaign,
                             String sectionName, String agentName) {
        PdfPTable t = fullWidth(new float[]{1.1f, 2.2f, 1.9f, 1.1f});

        t.addCell(labelCell("47.Section", Element.ALIGN_RIGHT));
        // Le modèle met en évidence la référence du producteur dans cette
        // boîte : la section si elle est renseignée, sinon le matricule de la
        // carte, qui est ce que le fichier source y montre.
        t.addCell(matriculeCell(orDash(sectionName != null ? sectionName : matricule(m))));
        t.addCell(labelCell("Campagne", Element.ALIGN_RIGHT));
        t.addCell(strongCell(campaign != null ? campaign.label : "-"));

        t.addCell(blankCell());
        t.addCell(blankCell());
        t.addCell(labelCell("48.Nom de l'agent de suivi", Element.ALIGN_RIGHT));
        t.addCell(highlightCell(orDash(agentName)));

        t.addCell(blankCell());
        t.addCell(blankCell());
        t.addCell(labelCell("50.Date de mise à jour données producteur-membre",
                Element.ALIGN_RIGHT));
        t.addCell(highlightCell(orDash(
                m.enrolment != null ? date(m.enrolment.dataCollectedAt) : null)));

        t.setSpacingAfter(8);
        return t;
    }

    private static PdfPTable band(String text) {
        PdfPTable t = fullWidth(1);
        PdfPCell cell = new PdfPCell(new Phrase(text, BAND_FONT));
        cell.setBackgroundColor(BAND);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(4);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        t.addCell(cell);
        t.setSpacingBefore(6);
        t.setSpacingAfter(3);
        return t;
    }

    /** Encadre une grille de champs, comme les blocs du modèle. */
    private static PdfPTable boxed(PdfPTable inner) {
        PdfPTable wrapper = fullWidth(1);
        PdfPCell cell = new PdfPCell(inner);
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(BOX_BORDER);
        cell.setBorderWidth(0.8f);
        cell.setPadding(3);
        wrapper.addCell(cell);
        return wrapper;
    }

    // ─── Grilles de champs ──────────────────────────────────────────

    private static PdfPTable fieldGrid() {
        return fullWidth(new float[]{2.3f, 1.5f, 2.5f, 1.5f});
    }

    private PdfPTable baseGrid(MemberEntity m) {
        // Le modèle imprime la pièce d'<em>identité</em> : une carte de
        // filière n'a rien à faire dans ces cases, même si elle vit
        // désormais dans la même liste.
        List<MemberIdentityDocument> docs = identityProofs(m);
        PdfPTable t = fieldGrid();

        row(t, "1.Identifiant interne Producteur", m.code, true,
               "7.Lieu de naissance", m.birthPlace);
        row(t, "2.Numéro carte producteur", matricule(m), false,
               "8.Type de pièce d'identité", docType(docs, 0));
        row(t, "3.Nom", m.lastName, false,
               "9.Numéro de la pièce d'identité", docNumber(docs, 0));
        row(t, "4.Prénoms", m.firstName, false,
               "10.Numéro d'identification national", nationalIdNumber(docs));
        row(t, "5.Genre (Homme ou Femme)", gender(m.gender), false,
               "11.Numéro de téléphone", m.phone);
        row(t, "6.Date de naissance", birth(m), false,
               "12.Situation matrimoniale (Marié(e), Célibataire…)", maritalStatus(m.maritalStatus));
        return t;
    }

    private PdfPTable householdGrid(MemberHousehold h) {
        PdfPTable t = fieldGrid();
        row(t, "13.Nombre de femmes", number(h == null ? null : h.spousesCount), false,
               "18.Nombre d'enfants de 5 à 17 ans", number(h == null ? null : h.children5to17));
        row(t, "14.Nombre d'enfants", number(h == null ? null : h.childrenCount), false,
               "19.Nombre d'enfants de + 17 ans", number(h == null ? null : h.childrenOver17));
        row(t, "15.Nombre de filles", number(h == null ? null : h.girlsCount), false,
               "20.Nombre d'enfants scolarisés", number(h == null ? null : h.childrenSchooled));
        row(t, "16.Nombre de garçons", number(h == null ? null : h.boysCount), false,
               "21.Nombre d'enfants non scolarisés", number(h == null ? null : h.childrenNotSchooled));
        row(t, "17.Nombre d'enfants de 0 à 4 ans", number(h == null ? null : h.children0to4), false,
               "22.À quel type d'activité les enfants sont soumis ?",
               h == null ? null : h.childrenActivity);
        return t;
    }

    private PdfPTable mainCropGrid(ParcelEntity p, Map<String, String> cropNames,
                                   CampaignEntity campaign) {
        PdfPTable t = fieldGrid();
        row(t, "23.Nom culture principale producteur (cacao, café, hévéa…)",
               cropLabel(p, cropNames), false,
               "26.Date de création plantation culture principale", plantingDate(p));
        row(t, "24.Superficie (ha) culture principale",
               p == null ? null : decimal(p.surfaceHa), false,
               "27.Latitude", coordinate(p, 1));
        row(t, "25.Production annuelle (kg) culture principale", estimate(p, campaign), false,
               "28.Longitude", coordinate(p, 0));
        return t;
    }

    /**
     * Quatre emplacements teintés, numérotés 29 à 44 : deux dans la colonne
     * de gauche, deux à droite, comme sur le modèle. Un emplacement sans
     * parcelle reste imprimé et vide.
     */
    /**
     * Quatre emplacements teintés, numérotés 29 à 44, appariés comme sur le
     * modèle : emplacements 1 et 3 sur les mêmes lignes, puis 2 et 4. Un
     * emplacement sans parcelle reste imprimé et vide, pour que la
     * numérotation ne bouge jamais d'une fiche à l'autre.
     */
    private PdfPTable secondaryCropsGrid(List<ParcelEntity> memberParcels,
                                         Map<String, String> cropNames,
                                         CampaignEntity campaign) {
        List<ParcelEntity> secondary = memberParcels.size() <= 1
                ? List.of()
                : memberParcels.subList(1, memberParcels.size());

        PdfPTable t = fieldGrid();
        pairSlots(t, 0, 2, secondary, cropNames, campaign);
        pairSlots(t, 1, 3, secondary, cropNames, campaign);
        return t;
    }

    /** Écrit les quatre lignes d'un couple d'emplacements (gauche, droite). */
    private void pairSlots(PdfPTable t, int leftSlot, int rightSlot, List<ParcelEntity> secondary,
                           Map<String, String> cropNames, CampaignEntity campaign) {
        String[] leftLabels = slotLabels(leftSlot);
        String[] rightLabels = slotLabels(rightSlot);
        String[] leftValues = slotValues(leftSlot, secondary, cropNames, campaign);
        String[] rightValues = slotValues(rightSlot, secondary, cropNames, campaign);

        for (int i = 0; i < leftLabels.length; i++) {
            t.addCell(tintedLabel(leftLabels[i], CROP_TINTS[leftSlot]));
            t.addCell(tintedValue(leftValues[i], CROP_TINTS[leftSlot]));
            t.addCell(tintedLabel(rightLabels[i], CROP_TINTS[rightSlot]));
            t.addCell(tintedValue(rightValues[i], CROP_TINTS[rightSlot]));
        }
    }

    private static String[] slotLabels(int slot) {
        int base = 29 + slot * 4;
        int rank = slot + 1;
        return new String[]{
                base + ".Nom culture secondaire " + rank,
                (base + 1) + ".Superficie (ha) culture secondaire " + rank,
                (base + 2) + ".Production annuelle (kg) culture secondaire " + rank,
                (base + 3) + ".Date création culture secondaire " + rank,
        };
    }

    private String[] slotValues(int slot, List<ParcelEntity> secondary,
                                Map<String, String> cropNames, CampaignEntity campaign) {
        ParcelEntity p = slot < secondary.size() ? secondary.get(slot) : null;
        return new String[]{
                cropLabel(p, cropNames),
                p == null ? null : decimal(p.surfaceHa),
                estimate(p, campaign),
                plantingDate(p),
        };
    }

    private PdfPTable additionalGrid(MemberEntity m) {
        PdfPTable t = fieldGrid();
        row(t, "45.Avez-vous été recensé lors du recensement pour la carte producteur ?",
               yesNo(m.enrolment == null ? null : m.enrolment.censusRegistered), false,
               "47.Si on vous a remis votre carte de producteur, pouvez-vous communiquer "
                       + "le numéro matricule de votre carte ?",
               matricule(m));
        row(t, "46.Vous a-t-on remis votre carte de producteur ?",
               yesNo(m.enrolment == null ? null : m.enrolment.producerCardIssued), false,
               "", null);
        return t;
    }

    // ─── Cellules ───────────────────────────────────────────────────

    private static void row(PdfPTable t, String leftLabel, String leftValue, boolean highlightLeft,
                            String rightLabel, String rightValue) {
        t.addCell(labelCell(leftLabel, Element.ALIGN_LEFT));
        t.addCell(highlightLeft ? highlightCell(orDash(leftValue)) : valueCell(leftValue));
        t.addCell(rightLabel == null || rightLabel.isEmpty()
                ? blankCell() : labelCell(rightLabel, Element.ALIGN_LEFT));
        t.addCell(rightLabel == null || rightLabel.isEmpty() ? blankCell() : valueCell(rightValue));
    }

    private static PdfPCell labelCell(String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, LABEL));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(3);
        cell.setHorizontalAlignment(alignment);
        return cell;
    }

    private static PdfPCell valueCell(String value) {
        PdfPCell cell = new PdfPCell(new Phrase(orDash(value), VALUE));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(3);
        return cell;
    }

    private static PdfPCell strongCell(String value) {
        PdfPCell cell = new PdfPCell(new Phrase(orDash(value), VALUE_STRONG));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(3);
        return cell;
    }

    /** Boîte teintée de l'en-tête : référence lisible à distance. */
    private static PdfPCell matriculeCell(String value) {
        PdfPCell cell = new PdfPCell(new Phrase(value, MATRICULE));
        cell.setBackgroundColor(HIGHLIGHT);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(5);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        return cell;
    }

    private static PdfPCell highlightCell(String value) {
        PdfPCell cell = new PdfPCell(new Phrase(value, VALUE_STRONG));
        cell.setBackgroundColor(HIGHLIGHT);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(4);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        return cell;
    }

    private static PdfPCell tintedLabel(String text, Color tint) {
        PdfPCell cell = labelCell(text, Element.ALIGN_LEFT);
        cell.setBackgroundColor(tint);
        return cell;
    }

    private static PdfPCell tintedValue(String value, Color tint) {
        PdfPCell cell = valueCell(value);
        cell.setBackgroundColor(tint);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        return cell;
    }

    private static PdfPCell blankCell() {
        PdfPCell cell = new PdfPCell(new Phrase(" ", VALUE));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(3);
        return cell;
    }

    private static PdfPTable fullWidth(int columns) {
        PdfPTable t = new PdfPTable(columns);
        t.setWidthPercentage(100);
        return t;
    }

    private static PdfPTable fullWidth(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        return t;
    }

    // ─── Résolution des valeurs ─────────────────────────────────────

    private static List<MemberIdentityDocument> identityDocuments(MemberEntity m) {
        if (m.identityDocuments != null && !m.identityDocuments.isEmpty()) {
            return m.identityDocuments;
        }
        if (m.idDocNumber != null && !m.idDocNumber.isBlank()) {
            return List.of(new MemberIdentityDocument(m.idDocType, m.idDocNumber, m.idCardFileId));
        }
        return List.of();
    }

    private static String docType(List<MemberIdentityDocument> docs, int index) {
        return index < docs.size() ? docs.get(index).type : null;
    }

    private static String docNumber(List<MemberIdentityDocument> docs, int index) {
        return index < docs.size() ? docs.get(index).number : null;
    }

    /**
     * Identifiant national : la pièce dont le type l'évoque, sinon la
     * deuxième pièce du dossier — le modèle attend une seconde référence
     * distincte de la carte d'identité.
     */
    private static String nationalIdNumber(List<MemberIdentityDocument> docs) {
        for (MemberIdentityDocument d : docs) {
            if (d.type == null) continue;
            String type = d.type.toLowerCase(Locale.ROOT);
            if (type.contains("nni") || type.contains("national")) return d.number;
        }
        return docNumber(docs, 1);
    }

    /**
     * Numéro de la carte du producteur : la carte du type déclaré comme
     * référence par la structure, à défaut la première renseignée. Même
     * règle que sur le reçu d'achat, pour que les deux documents portent
     * le même numéro.
     */
    private String matricule(MemberEntity m) {
        List<MemberIdentityDocument> cards = documentsOfTypes(m, producerRefKeys.identifierTypeNames());
        if (cards.isEmpty()) return null;
        String referenceType = preferences.current().producerReferenceCodeType;
        if (referenceType != null && !referenceType.isBlank()) {
            for (MemberIdentityDocument d : cards) {
                if (d.type != null && d.type.trim().equalsIgnoreCase(referenceType.trim())) {
                    return d.number != null ? d.number.trim() : null;
                }
            }
        }
        return cards.get(0).number != null ? cards.get(0).number.trim() : null;
    }

    /** Pièces qui établissent l'identité, dans l'ordre du dossier. */
    private List<MemberIdentityDocument> identityProofs(MemberEntity m) {
        java.util.Set<String> proofs = producerRefKeys.identityProofTypeNames();
        if (proofs == null) return identityDocuments(m);
        List<MemberIdentityDocument> filtered = documentsOfTypes(m, proofs);
        return filtered.isEmpty() && m.idDocNumber != null && !m.idDocNumber.isBlank()
                ? List.of(new MemberIdentityDocument(m.idDocType, m.idDocNumber, m.idCardFileId))
                : filtered;
    }

    private static List<MemberIdentityDocument> documentsOfTypes(MemberEntity m,
                                                                java.util.Set<String> typeNames) {
        if (m.identityDocuments == null || typeNames == null) return List.of();
        return m.identityDocuments.stream()
                .filter(d -> d != null && d.type != null && d.number != null && !d.number.isBlank())
                .filter(d -> typeNames.contains(d.type.trim().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static String cropLabel(ParcelEntity p, Map<String, String> cropNames) {
        if (p == null) return null;
        if (p.cropCode != null) return cropNames.getOrDefault(p.cropCode, p.cropCode);
        return p.variety;
    }

    private static String estimate(ParcelEntity p, CampaignEntity campaign) {
        if (p == null || p.campaignYields == null || campaign == null) return null;
        return p.campaignYields.stream()
                .filter(y -> campaign.id.equals(y.campaignId))
                .findFirst()
                .map(y -> decimal(y.estimateKg))
                .orElse(null);
    }

    private static String plantingDate(ParcelEntity p) {
        if (p == null) return null;
        if (p.plantingDate != null) return date(p.plantingDate);
        return p.plantingYear != null ? String.valueOf(p.plantingYear) : null;
    }

    private static String coordinate(ParcelEntity p, int index) {
        if (p == null || p.gpsCenter == null || p.gpsCenter.size() < 2) return null;
        Double v = p.gpsCenter.get(index);
        return v == null ? null : String.valueOf(v);
    }

    private static String birth(MemberEntity m) {
        if (m.birthDate != null) return date(m.birthDate);
        return m.birthYear != null ? String.valueOf(m.birthYear) : null;
    }

    private static String gender(MemberGender g) {
        if (g == null) return null;
        return switch (g) {
            case MALE -> "Homme";
            case FEMALE -> "Femme";
            case UNKNOWN -> null;
        };
    }

    private static String maritalStatus(MemberMaritalStatus s) {
        if (s == null) return null;
        return switch (s) {
            case SINGLE -> "Célibataire";
            case MARRIED -> "Marié(e)";
            case COHABITING -> "Union libre";
            case WIDOWED -> "Veuf(ve)";
            case DIVORCED -> "Divorcé(e)";
            case UNKNOWN -> null;
        };
    }

    private static String yesNo(Boolean b) {
        if (b == null) return null;
        return b ? "Oui" : "Non";
    }

    private static String number(Integer i) {
        return i == null ? null : String.valueOf(i);
    }

    private static String decimal(BigDecimal d) {
        return d == null ? null : d.stripTrailingZeros().toPlainString();
    }

    private static String date(LocalDate d) {
        return d == null ? null : d.format(FR_DATE);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
