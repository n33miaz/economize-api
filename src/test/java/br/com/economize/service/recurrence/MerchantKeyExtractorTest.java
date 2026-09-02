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
    void bankNamesAndOperationVerbsNeverIdentifyTheEntity() {
        // o nome do banco acompanha o CDB, o cashback e o estorno; "compra" é o
        // verbo da operação — nenhum deles é a entidade
        assertThat(MerchantKeyExtractor.extract("Resgate: \"CDB Cofrinho BANCO INTER SA\"").tokens())
                .containsExactly("resgate", "cdb", "cofrinho");
        assertThat(MerchantKeyExtractor.extract("Cashback: \"ITAU PRE 20GB MENSAL\"").tokens())
                .containsExactly("cashback", "pre");
        assertThat(MerchantKeyExtractor.extract("Compra Meio De Transporte: \"No estabelecimento TRILHOS SP\"").tokens())
                .containsExactly("meio", "transporte", "trilhos");
    }

    @Test
    void cityTokensAreRemovedFromTheDuplicatedMerchantCopyToo() {
        // MEMO truncado + NAME reformatado: a cidade da cópia do meio escapava do
        // corte terminal e virava o token mais persistente do histórico
        assertThat(MerchantKeyExtractor.extract(
                "Compra no debito: \"No estabelecimento 123 JOAO VITOR G VILAREAL 1\" Joao Vitor G Vilareal Bra")
                .tokens()).containsExactly("joao", "vitor");
    }

    @Test
    void transferCounterpartyIsKeyedByFirstNameAndDominantToken() {
        Map<String, Integer> monthCounts = Map.of("alice", 6, "santos", 20, "araujo", 8);
        MerchantKeyExtractor.Extraction alice =
                MerchantKeyExtractor.extract("Pix recebido: \"Cp :123-Alice dos Santos Araujo\"");
        assertThat(alice.transferLike()).isTrue();
        assertThat(MerchantKeyExtractor.entityKey(alice, alice.tokens(), monthCounts))
                .isEqualTo("alice santos");
        // outra pessoa com o mesmo sobrenome dominante fica em outra chave
        MerchantKeyExtractor.Extraction other =
                MerchantKeyExtractor.extract("Pix enviado: \"Cp :456-Bruno Santos Lima\"");
        assertThat(MerchantKeyExtractor.entityKey(other, other.tokens(), monthCounts))
                .isEqualTo("bruno santos");
        // "null" literal do banco de origem ausente não vira primeiro nome
        MerchantKeyExtractor.Extraction noBank =
                MerchantKeyExtractor.extract("Pix enviado: \"Cp :456-null Bruno Santos Lima\"");
        assertThat(MerchantKeyExtractor.entityKey(noBank, noBank.tokens(), monthCounts))
                .isEqualTo("bruno santos");
    }

    @Test
    void transferKeyCompletesWithSecondTokenWhenFirstNameDominates() {
        // "maria" é o token mais persistente por ser comum: duas Marias não podem
        // colidir numa chave de um token só
        Map<String, Integer> monthCounts = Map.of("maria", 13, "eduarda", 5, "bezerra", 5, "luiza", 2, "ribeiro", 4);
        MerchantKeyExtractor.Extraction first =
                MerchantKeyExtractor.extract("Pix enviado: \"Cp :1-Maria Eduarda Bezerra\"");
        MerchantKeyExtractor.Extraction second =
                MerchantKeyExtractor.extract("Pix enviado: \"Cp :1-Maria Luiza Ribeiro\"");
        assertThat(MerchantKeyExtractor.entityKey(first, first.tokens(), monthCounts))
                .isEqualTo("maria bezerra");
        assertThat(MerchantKeyExtractor.entityKey(second, second.tokens(), monthCounts))
                .isEqualTo("maria ribeiro");
    }

    @Test
    void purchasesKeepTheSingleDominantTokenAcrossLabelDrift() {
        // compra no cartão não é transferência: a chave continua o dominante
        // sozinho, que é o que sobrevive à troca de rótulo do estabelecimento
        Map<String, Integer> monthCounts = Map.of("luminora", 6, "axis", 3);
        MerchantKeyExtractor.Extraction purchase =
                MerchantKeyExtractor.extract("No Estabelecimento Axis Luminora Sao Paulo Bra");
        assertThat(purchase.transferLike()).isFalse();
        assertThat(MerchantKeyExtractor.entityKey(purchase, purchase.tokens(), monthCounts))
                .isEqualTo("luminora");
    }

    @Test
    void deriveKeyComposesTransferHintsLikeDetectionDoes() {
        assertThat(MerchantKeyExtractor.deriveKey("Pix Maria Souza")).isEqualTo("maria souza");
        assertThat(MerchantKeyExtractor.deriveKey("Aluguel")).isEqualTo("aluguel");
    }

    @Test
    void transferStopsAtTheMaskedDocumentOfTheCounterparty() {
        // O que vem depois do CPF mascarado é banco de destino, agência e conta.
        // "MERCADO PAGO IP LTDA" não está em lista de banco nenhuma e acompanha
        // TODO PIX para aquele destino: sem o corte, ele ganha mais meses que o
        // nome de quem recebe e vira a identidade da série
        MerchantKeyExtractor.Extraction nubank = MerchantKeyExtractor.extract(
                "Transferência enviada pelo Pix - ANA BEATRIZ COSTA - •••.123.456-•• - "
                        + "MERCADO PAGO IP LTDA (0323) Agência: 1 Conta: 12345678-9");

        assertThat(nubank.tokens()).containsExactly("ana", "beatriz", "costa");
    }

    @Test
    void transferWithoutMaskKeepsTheWholeDescription() {
        // O formato do Inter não traz documento: cortar aqui perderia o nome
        MerchantKeyExtractor.Extraction inter =
                MerchantKeyExtractor.extract("Pix enviado: \"Cp :260-Ana Beatriz Costa\"");

        assertThat(inter.tokens()).containsExactly("ana", "beatriz", "costa");
    }

    @Test
    void purchaseWithMaskedCardNumberIsNotTruncated() {
        // O corte vale só para transferência: em compra, a máscara é o número do
        // cartão e o nome do estabelecimento vem DEPOIS dela
        MerchantKeyExtractor.Extraction compra =
                MerchantKeyExtractor.extract("Compra cartão final ****1234 Padaria Aurora");

        assertThat(compra.transferLike()).isFalse();
        assertThat(compra.tokens()).contains("padaria", "aurora");
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
