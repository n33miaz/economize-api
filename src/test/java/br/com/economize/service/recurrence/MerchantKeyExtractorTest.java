package br.com.economize.service.recurrence;

import br.com.economize.model.RecurringSeries;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantKeyExtractorTest {

    @Test
    void anchorsCardBillEvenWhenLabelSharesNothingElse() {
        // as duas formas reais da fatura não têm token estável além da palavra
        assertThat(MerchantKeyExtractor.extract("Pagamento efetuado | Fatura cartão Aurora").anchor())
                .isEqualTo("fatura");
        assertThat(MerchantKeyExtractor.extract("Pagamento efetuado | Pagamento Fatura - JOANA PRADO").anchor())
                .isEqualTo("fatura");
    }

    @Test
    void anchorsSalaryFromPortabilityLabel() {
        assertThat(MerchantKeyExtractor.extract("Salário recebido - Portabilidade").anchor())
                .isEqualTo("salario");
    }

    @Test
    void anchorMatchesWholeTokensOnlyNeverSubstrings() {
        // "faturamento" contém "fatura", mas é outra entidade (receita de MEI):
        // casar por substring fundiria as duas séries
        MerchantKeyExtractor.Extraction billing =
                MerchantKeyExtractor.extract("Recebimento faturamento mensal");
        assertThat(billing.anchor()).isNull();
        assertThat(billing.tokens()).containsExactly("recebimento", "faturamento");
    }

    @Test
    void stripsAcquirerPrefixAndCitySuffixFromSubscriptions() {
        assertThat(MerchantKeyExtractor.extract("Dm*melodia Sao Paulo Bra").tokens())
                .containsExactly("melodia");
        assertThat(MerchantKeyExtractor.extract("Ebn*melodia Curitiba Bra").tokens())
                .containsExactly("melodia");
        assertThat(MerchantKeyExtractor.extract("No Estabelecimento Dm *melodia Stockholm Bra").tokens())
                .containsExactly("melodia");
    }

    @Test
    void dropsStopwordsFromUtilityLabels() {
        assertThat(MerchantKeyExtractor.extract("Pagamento AQUANORTE | AQUANORTE").tokens())
                .containsExactly("aquanorte");
        assertThat(MerchantKeyExtractor.extract("Pagamento de Convênio | AQUANORTE").tokens())
                .containsExactly("aquanorte");
    }

    @Test
    void extractsCadenceHintAndDropsPlanSizeTokens() {
        MerchantKeyExtractor.Extraction monthly = MerchantKeyExtractor.extract("Zetacel Pre 10gb Mensal");
        assertThat(monthly.tokens()).containsExactly("zetacel", "pre");
        assertThat(monthly.cadenceHint()).isEqualTo(RecurringSeries.Cadence.MONTHLY);

        MerchantKeyExtractor.Extraction quarterly = MerchantKeyExtractor.extract("Zetacel Cel Trimestral 20GB");
        assertThat(quarterly.tokens()).containsExactly("zetacel", "cel");
        assertThat(quarterly.cadenceHint()).isEqualTo(RecurringSeries.Cadence.QUARTERLY);
    }

    @Test
    void dominantTokenSurvivesLabelChange() {
        // "luminora" está nas duas grafias ao longo dos meses; os satélites não
        Map<String, Integer> monthCounts = Map.of("luminora", 6, "axis", 3, "metropolitana", 3);
        assertThat(MerchantKeyExtractor.dominantToken(List.of("luminora", "axis"), monthCounts))
                .isEqualTo("luminora");
        assertThat(MerchantKeyExtractor.dominantToken(List.of("luminora", "metropolitana"), monthCounts))
                .isEqualTo("luminora");
    }

    @Test
    void dominantTokenTieBreaksByLaterPositionForPersonNames() {
        Map<String, Integer> monthCounts = Map.of("joana", 4, "prado", 4, "sales", 4);
        assertThat(MerchantKeyExtractor.dominantToken(List.of("joana", "prado"), monthCounts))
                .isEqualTo("prado");
        assertThat(MerchantKeyExtractor.dominantToken(List.of("joana", "sales"), monthCounts))
                .isEqualTo("sales");
    }

    @Test
    void mentionsNameRequiresTwoTokensOfTheOwnerName() {
        List<String> owner = MerchantKeyExtractor.nameTokens("Carlos Pereira");
        assertThat(MerchantKeyExtractor.mentionsName(
                MerchantKeyExtractor.lightNormalize("Pix enviado para Carlos Pereira"), owner)).isTrue();
        // homônima parcial não é o titular
        assertThat(MerchantKeyExtractor.mentionsName(
                MerchantKeyExtractor.lightNormalize("Pix recebido de Carlos Andrade"), owner)).isFalse();
    }
}
