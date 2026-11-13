package br.com.economize.service.statement.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * O custo das expressões regulares diante de arquivo hostil.
 *
 * <p>O teto de upload (10 MB) limita a memória, não a CPU: uma regex que volta
 * atrás de forma quadrática ou cúbica dentro desses 10 MB prende uma thread do
 * boundedElastic por minutos. Cada caso aqui foi medido estourando o prazo
 * ANTES da correção; o prazo é folgado de propósito, porque o custo linear
 * termina em milissegundos e o que se quer pegar é a explosão, não um
 * microbenchmark.
 */
@DisplayName("Parsers — custo linear diante de entrada hostil")
class StatementParserRegexCostTest {

    private static final Duration PRAZO = Duration.ofSeconds(5);

    private static ByteArrayInputStream bytes(String conteudo) {
        return new ByteArrayInputStream(conteudo.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("OFX: 200 mil <STMTTRN> sem fechamento não lê nada e termina logo")
    void ofxBlocosSemFechamento() {
        String ofx = "OFXHEADER:100\n<OFX>" + "<STMTTRN>x".repeat(200_000);
        List<ParsedTransaction> lidas = assertTimeoutPreemptively(PRAZO,
                () -> new OfxParser().parse(bytes(ofx)));
        assertThat(lidas).isEmpty();
    }

    @Test
    @DisplayName("OFX: 200 mil <LEDGERBAL> sem fecho não acha saldo e termina logo")
    void ofxSaldoSemFecho() {
        String ofx = "OFXHEADER:100\n<OFX>" + "<LEDGERBAL>x".repeat(200_000);
        StatementBalance saldo = assertTimeoutPreemptively(PRAZO,
                () -> new OfxParser().parseBalance(bytes(ofx)));
        assertThat(saldo).isNull();
    }

    @Test
    @DisplayName("TXT: linha de 5 MB com datas coladas e sem valor não lê nada")
    void txtLinhaGiganteDeDatas() {
        String linha = "01/01/2020 a".repeat(5_000_000 / 12);
        List<ParsedTransaction> lidas = assertTimeoutPreemptively(PRAZO,
                () -> new TxtParser().parse(bytes(linha)));
        assertThat(lidas).isEmpty();
    }

    @Test
    @DisplayName("TXT: data seguida de 1 MB de espaços não lê nada")
    void txtCorridaDeEspacos() {
        String linha = "01/01/2020" + " ".repeat(1_000_000) + "x";
        List<ParsedTransaction> lidas = assertTimeoutPreemptively(PRAZO,
                () -> new TxtParser().parse(bytes(linha)));
        assertThat(lidas).isEmpty();
    }

    @Test
    @DisplayName("TXT: 500 linhas de 10 mil caracteres hostis não leem nada")
    void txtMuitasLinhasLongas() {
        String linha = "01/01/2020 a".repeat(10_000 / 12) + "\n";
        String arquivo = linha.repeat(500);
        List<ParsedTransaction> lidas = assertTimeoutPreemptively(PRAZO,
                () -> new TxtParser().parse(bytes(arquivo)));
        assertThat(lidas).isEmpty();
    }

    @Test
    @DisplayName("TXT: data e valor sem descrição continua aceito, como sempre foi")
    void txtSemDescricaoContinuaAceito() {
        List<ParsedTransaction> lidas = new TxtParser().parse(bytes("01/01/2020   150,00"));
        assertThat(lidas).singleElement().satisfies(tx -> {
            assertThat(tx.getDescription()).isEmpty();
            assertThat(tx.getAmount()).isEqualByComparingTo(new BigDecimal("150.00"));
        });
    }

    @Test
    @DisplayName("Mercado Pago: corrida de 500 mil dígitos não lê nada")
    void mercadoPagoCorridaDeDigitos() {
        String texto = "01-01-2020 " + "1".repeat(500_000) + " x";
        List<ParsedTransaction> lidas = assertTimeoutPreemptively(PRAZO,
                () -> MercadoPagoPdfLayout.parse(texto));
        assertThat(lidas).isEmpty();
    }

    @Test
    @DisplayName("Mercado Pago: data seguida de 500 mil espaços não lê nada")
    void mercadoPagoCorridaDeEspacos() {
        String texto = "01-01-2020" + " ".repeat(500_000) + "x";
        List<ParsedTransaction> lidas = assertTimeoutPreemptively(PRAZO,
                () -> MercadoPagoPdfLayout.parse(texto));
        assertThat(lidas).isEmpty();
    }
}
