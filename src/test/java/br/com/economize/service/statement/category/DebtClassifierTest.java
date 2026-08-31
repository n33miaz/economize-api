package br.com.economize.service.statement.category;

import br.com.economize.service.statement.category.DebtClassifier.DebtKind;
import br.com.economize.service.statement.category.DebtClassifier.DebtSignal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DebtClassifierTest {

    // ------------------------------------------------------- o caso perigoso

    @Test
    void dataDeDezembroNaoViraParcela() {
        // "03/12" em extrato brasileiro é DATA. Aceitar o padrão solto marcaria
        // toda compra de dezembro como parcelada — é o falso positivo que a
        // regra inteira existe para evitar
        assertThat(DebtClassifier.classify("COMPRA 03/12 PADARIA CENTRAL").kind())
                .isEqualTo(DebtKind.NONE);
        assertThat(DebtClassifier.classify("IFOOD 05/11").kind()).isEqualTo(DebtKind.NONE);
    }

    @Test
    void totalAcimaDeDozeEInequivocoMesmoSemAPalavraParcela() {
        // nenhum mês vai além de 12: "13/24" só pode ser parcela
        DebtSignal signal = DebtClassifier.classify("MAGAZINE LUIZA 13/24");
        assertThat(signal.kind()).isEqualTo(DebtKind.INSTALLMENT);
        assertThat(signal.installment()).isEqualTo(13);
        assertThat(signal.total()).isEqualTo(24);
    }

    @Test
    void comAPalavraParcelaOPadraoAteDozeVale() {
        DebtSignal signal = DebtClassifier.classify("PARC 03/12 LOJAS AMERICANAS");
        assertThat(signal.kind()).isEqualTo(DebtKind.INSTALLMENT);
        assertThat(signal.installment()).isEqualTo(3);
        assertThat(signal.total()).isEqualTo(12);
    }

    // --------------------------------------------------- ordem dos tipos

    @Test
    void parcelamentoDeFaturaERotativoENaoParcelamentoDeCompra() {
        // tem a palavra "parcelamento", mas é a dívida mais cara do país e
        // precisa do alarme próprio
        assertThat(DebtClassifier.classify("PARCELAMENTO DE FATURA 02/10").kind())
                .isEqualTo(DebtKind.REVOLVING);
    }

    @Test
    void parcelaDeFinanciamentoEFinanciamento() {
        // o tipo mais específico é o que informa: a parcela tem juros e
        // amortização, e isso muda o significado do número
        DebtSignal signal = DebtClassifier.classify("PARCELA 07/48 FINANCIAMENTO VEICULO");
        assertThat(signal.kind()).isEqualTo(DebtKind.FINANCING);
        assertThat(signal.installment()).isEqualTo(7);
        assertThat(signal.total()).isEqualTo(48);
        assertThat(signal.remaining()).isEqualTo(41);
    }

    @Test
    void reconheceCadaTipoPelosNomesQueOExtratoUsa() {
        assertThat(DebtClassifier.classify("JUROS ROTATIVO CARTAO").kind())
                .isEqualTo(DebtKind.REVOLVING);
        assertThat(DebtClassifier.classify("PAGAMENTO MINIMO").kind())
                .isEqualTo(DebtKind.REVOLVING);
        assertThat(DebtClassifier.classify("CONSORCIO IMOVEL GRUPO 4521").kind())
                .isEqualTo(DebtKind.CONSORTIUM);
        assertThat(DebtClassifier.classify("CONSÓRCIO NACIONAL").kind())
                .isEqualTo(DebtKind.CONSORTIUM);
        assertThat(DebtClassifier.classify("CRED IMOBILIARIO CAIXA").kind())
                .isEqualTo(DebtKind.FINANCING);
        assertThat(DebtClassifier.classify("LEASING VEICULO").kind())
                .isEqualTo(DebtKind.FINANCING);
        assertThat(DebtClassifier.classify("EMPRESTIMO PESSOAL").kind())
                .isEqualTo(DebtKind.LOAN);
        assertThat(DebtClassifier.classify("EMPRÉSTIMO CONSIGNADO").kind())
                .isEqualTo(DebtKind.LOAN);
        assertThat(DebtClassifier.classify("CHEQUE ESPECIAL JUROS").kind())
                .isEqualTo(DebtKind.LOAN);
    }

    @Test
    void acentoNaoAtrapalha() {
        assertThat(DebtClassifier.classify("PRESTAÇÃO 02/36").kind())
                .isEqualTo(DebtKind.INSTALLMENT);
    }

    // ----------------------------------------------------------- limites

    @Test
    void despesaComumNaoViraDivida() {
        assertThat(DebtClassifier.classify("SUPERMERCADO PAO DE ACUCAR").kind())
                .isEqualTo(DebtKind.NONE);
        assertThat(DebtClassifier.classify("NETFLIX").kind()).isEqualTo(DebtKind.NONE);
        assertThat(DebtClassifier.classify("UBER TRIP").kind()).isEqualTo(DebtKind.NONE);
    }

    @Test
    void descricaoVaziaOuNulaNaoQuebra() {
        assertThat(DebtClassifier.classify(null).kind()).isEqualTo(DebtKind.NONE);
        assertThat(DebtClassifier.classify("").kind()).isEqualTo(DebtKind.NONE);
        assertThat(DebtClassifier.classify("   ").kind()).isEqualTo(DebtKind.NONE);
    }

    @Test
    void parcelaAlemDoTotalEDescartada() {
        // "24/13" não descreve parcelamento nenhum
        assertThat(DebtClassifier.classify("LOJA 24/13").installment()).isNull();
    }

    @Test
    void totalAbsurdoNaoViraParcelamento() {
        // acima de 96 parcelas é outro número dentro da descrição
        assertThat(DebtClassifier.classify("REF 12/99").kind()).isEqualTo(DebtKind.NONE);
    }

    @Test
    void dataCompletaNaoEConfundidaComParcela() {
        // 03/12/2026: o lookahead impede casar "03/12" deixando "/2026" para trás
        assertThat(DebtClassifier.classify("COMPRA 03/12/2026 MERCADO").kind())
                .isEqualTo(DebtKind.NONE);
    }

    @Test
    void semParcelaNoTextoOsCamposFicamNulos() {
        DebtSignal signal = DebtClassifier.classify("EMPRESTIMO PESSOAL");
        assertThat(signal.installment()).isNull();
        assertThat(signal.total()).isNull();
        assertThat(signal.remaining()).isNull();
    }

    @Test
    void aUltimaParcelaNaoDeixaNadaFaltando() {
        assertThat(DebtClassifier.classify("PARCELA 12/12 LOJA").remaining()).isZero();
    }

    @Test
    void isDebtSeparaDividaDeDespesa() {
        assertThat(DebtClassifier.classify("FINANCIAMENTO IMOVEL").isDebt()).isTrue();
        assertThat(DebtClassifier.classify("PADARIA").isDebt()).isFalse();
    }
}
