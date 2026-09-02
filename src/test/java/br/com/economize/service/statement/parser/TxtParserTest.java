package br.com.economize.service.statement.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O parser de extrato em texto puro (EC-155).
 *
 * <p>É o formato mais bagunçado que entra no sistema: cada banco alinha as
 * colunas do seu jeito, põe ou não o "R$", usa ponto ou espaço no milhar. O
 * parser trabalha por expressão regular, e regex sem teste é a definição de
 * código que ninguém sabe o que faz.
 */
@DisplayName("TxtParser — o extrato em texto puro")
class TxtParserTest {

    private final TxtParser parser = new TxtParser();

    private List<ParsedTransaction> ler(String conteudo) {
        InputStream input = new ByteArrayInputStream(conteudo.getBytes(StandardCharsets.UTF_8));
        return parser.parse(input);
    }

    @Test
    @DisplayName("Declara o formato que atende")
    void declaraOFormato() {
        assertThat(parser.format()).isEqualTo(StatementFormat.TXT);
    }

    @Test
    @DisplayName("Lê data, descrição e valor de uma linha comum")
    void leLinhaComum() {
        List<ParsedTransaction> lidas = ler("05/03/2026   SUPERMERCADO SILVA   -150,75");

        assertThat(lidas).hasSize(1);
        ParsedTransaction tx = lidas.get(0);
        assertThat(tx.getDate().toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(tx.getDate().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(tx.getDescription()).isEqualTo("SUPERMERCADO SILVA");
        assertThat(tx.getAmount()).isEqualByComparingTo("-150.75");
        assertThat(tx.getType()).isEqualTo("DEBIT");
    }

    @Test
    @DisplayName("Aceita o R$ colado ou separado do número")
    void aceitaSimboloDeMoeda() {
        assertThat(ler("05/03/2026   PADARIA   R$ 12,50").get(0).getAmount())
                .isEqualByComparingTo("12.50");
        assertThat(ler("05/03/2026   PADARIA   R$12,50").get(0).getAmount())
                .isEqualByComparingTo("12.50");
        assertThat(ler("05/03/2026   PADARIA   -R$ 12,50").get(0).getAmount())
                .isEqualByComparingTo("-12.50");
    }

    @Test
    @DisplayName("Milhar com ponto vira número, não vira centavo trocado")
    void milharComPonto() {
        // "4.400,00" lido como decimal americano seria R$ 4,40
        assertThat(ler("05/03/2026   SALARIO EMPRESA   4.400,00").get(0).getAmount())
                .isEqualByComparingTo("4400.00");
    }

    @Test
    @DisplayName("Milhar com espaço também é aceito")
    void milharComEspaco() {
        assertThat(ler("05/03/2026   TRANSFERENCIA   1 234,56").get(0).getAmount())
                .isEqualByComparingTo("1234.56");
    }

    @Test
    @DisplayName("Valor sem centavos é lido como inteiro")
    void valorSemCentavos() {
        assertThat(ler("05/03/2026   PIX RECEBIDO   500").get(0).getAmount())
                .isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("O sinal decide entrada e saída")
    void sinalDecideOTipo() {
        assertThat(ler("05/03/2026   ENTRADA   100,00").get(0).getType()).isEqualTo("CREDIT");
        assertThat(ler("05/03/2026   SAIDA   -100,00").get(0).getType()).isEqualTo("DEBIT");
    }

    @Test
    @DisplayName("Cabeçalho, rodapé e linha em branco são ignorados sem erro")
    void ruidoEIgnorado() {
        List<ParsedTransaction> lidas = ler("""
                BANCO EXEMPLO S.A.
                EXTRATO DE CONTA CORRENTE — MARÇO/2026

                05/03/2026   MERCADO   -150,75
                06/03/2026   SALARIO   4.400,00

                SALDO FINAL: 4.249,25
                """);

        // A linha de saldo NÃO é transação: se entrasse, o mês inteiro
        // apareceria com uma receita fantasma do tamanho do saldo
        assertThat(lidas).extracting(ParsedTransaction::getDescription)
                .containsExactly("MERCADO", "SALARIO");
    }

    @Test
    @DisplayName("Data inválida no formato certo não vira transação")
    void dataImpossivelEDescartada() {
        // 32 de março casa com o padrão dd/MM/yyyy mas não existe
        assertThat(ler("32/03/2026   MERCADO   -10,00")).isEmpty();
    }

    @Test
    @DisplayName("Cada linha ganha id externo próprio, estável entre importações")
    void idExternoEEstavel() {
        String conteudo = """
                05/03/2026   MERCADO   -10,00
                05/03/2026   MERCADO   -10,00
                """;

        List<ParsedTransaction> primeira = ler(conteudo);
        List<ParsedTransaction> segunda = ler(conteudo);

        // Duas linhas idênticas no mesmo dia existem de verdade (duas compras):
        // o id distingue as duas pela posição, e repete na reimportação
        assertThat(primeira.get(0).getExternalId()).isNotEqualTo(primeira.get(1).getExternalId());
        assertThat(primeira).extracting(ParsedTransaction::getExternalId)
                .containsExactlyElementsOf(
                        segunda.stream().map(ParsedTransaction::getExternalId).toList());
    }

    @Test
    @DisplayName("A sequência do id conta TODAS as linhas, não só as válidas")
    void sequenciaContaLinhasDoArquivo() {
        // Assim o id de uma transação não muda quando uma linha de ruído acima
        // dela passa a casar (ou deixa de casar) com o padrão
        List<ParsedTransaction> lidas = ler("""
                CABECALHO
                05/03/2026   MERCADO   -10,00
                """);

        assertThat(lidas.get(0).getExternalId()).isEqualTo("TXT-1-05/03/2026");
    }

    @Test
    @DisplayName("Arquivo vazio devolve lista vazia, sem erro")
    void arquivoVazio() {
        assertThat(ler("")).isEmpty();
    }

    @Test
    @DisplayName("Acento sobrevive à leitura em UTF-8")
    void acentoSobrevive() {
        assertThat(ler("05/03/2026   FARMÁCIA SÃO JOÃO   -32,90").get(0).getDescription())
                .isEqualTo("FARMÁCIA SÃO JOÃO");
    }
}
