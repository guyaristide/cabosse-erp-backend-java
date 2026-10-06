package com.ntech.cabosse.shared.exception;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce qu'une erreur montre, et ce qu'elle garde pour le journal.
 *
 * <p>Un bordereau refusé affichait « Write operation error on MongoDB
 * server mongo:27017. WriteError{code=11000, message=E11000 duplicate key
 * error collection: tenant_01a0… } » à quelqu'un qui pesait des sacs
 * (constaté le 06/10/2026). Le détail technique a sa place, au journal,
 * pas sur l'écran.</p>
 */
class UserFacingReasonTest {

    @Test
    void un_refus_que_nous_avons_ecrit_passe_tel_quel() {
        String reason = UserFacingReason.of(
                new BusinessException("Le producteur SORO n'a pas de compte de tiers."));
        assertThat(reason).isEqualTo("Le producteur SORO n'a pas de compte de tiers.");
    }

    @Test
    void les_autres_refus_metier_passent_aussi() {
        assertThat(UserFacingReason.of(new NotFoundException("Article introuvable.")))
                .isEqualTo("Article introuvable.");
        assertThat(UserFacingReason.of(new ConflictException("Le reçu a déjà été comptabilisé.")))
                .isEqualTo("Le reçu a déjà été comptabilisé.");
    }

    @Test
    void une_panne_technique_ne_sort_jamais_telle_quelle() {
        String mongo = "Write operation error on MongoDB server mongo:27017. "
                + "WriteError{code=11000, message='E11000 duplicate key error collection: "
                + "tenant_01a0c4141e29769197374c149c3e16b6.members index: uniq_members_code'}";
        String reason = UserFacingReason.of(new IllegalStateException(mongo), "ligne 12");

        assertThat(reason)
                .doesNotContain("MongoDB")
                .doesNotContain("E11000")
                .doesNotContain("tenant_")
                .contains("erreur technique");
    }

    @Test
    void la_panne_laisse_une_reference_a_dicter() {
        String reason = UserFacingReason.of(new UncheckedIOException(new IOException("Stream closed")));
        // Huit caractères, lisibles au téléphone : c'est ce que le support
        // cherchera dans le journal.
        assertThat(reason).containsPattern("[0-9A-F]{8}");
        assertThat(reason).doesNotContain("Stream closed");
    }

    @Test
    void un_refus_sans_message_retombe_sur_la_phrase_generique() {
        assertThat(UserFacingReason.of(new BusinessException((String) null)))
                .contains("erreur technique");
        assertThat(UserFacingReason.of(null)).isNotBlank();
    }

    /**
     * Le cliquet : plus personne ne recolle un message d'exception dans ce
     * qui s'affiche. Le motif se construit par {@link UserFacingReason}.
     */
    @Test
    void aucune_source_ne_recolle_un_message_d_exception_dans_l_affichage() {
        // Un message du catalogue paramétré par le texte d'une exception :
        // c'est exactement la fuite qu'on vient de boucher.
        Pattern intoCatalog = Pattern.compile("Messages\\.msg\\([^;]*?\\w+\\.getMessage\\(\\)");
        // Le motif d'une ligne écartée dans un compte rendu d'import.
        Pattern intoRow = Pattern.compile("new FieldIssue\\([^;]*?\\w+\\.getMessage\\(\\)");

        List<String> offenders = new ArrayList<>();
        Path root = Path.of("src/main/java/com/ntech/cabosse");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (intoCatalog.matcher(source).find() || intoRow.matcher(source).find()) {
                    offenders.add(root.relativize(file).toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(offenders)
                .as("passer par UserFacingReason.of(e) plutôt que e.getMessage()")
                .isEmpty();
    }
}
