package br.com.economize.dto.statement;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.StatementUpload;
import br.com.economize.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EC-195 — de onde veio cada número, e quando ele entrou.
 *
 * <p>São as duas perguntas que alguém faz quando desconfia de um valor na
 * tela, e até aqui nenhuma resposta da API respondia nenhuma das duas. O
 * concorrente também não responde: no tour dele, a Análise afirmou "Sem gastos
 * por categoria" enquanto a home mostrava R$ 810,61 em cinco categorias, e não
 * havia nada em tela que dissesse de onde qualquer um dos dois números tinha
 * saído.
 *
 * <p>A prova que este teste guarda: <b>nenhuma linha fica sem resposta</b>.
 */
class ProvenanceTest {

    private static final User DONO = User.builder()
            .id(UUID.randomUUID()).email("dono@economize.test").name("Dono").password("x").build();

    @Test
    @DisplayName("Linha com conta de provedor veio pela conexão")
    void comContaEConexao() {
        BankTransaction tx = tx();
        tx.setAccountId(UUID.randomUUID());

        assertThat(tx.importSource()).isEqualTo(BankTransaction.ImportSource.CONNECTION);
    }

    @Test
    @DisplayName("Linha só com upload veio por arquivo")
    void soComUploadEArquivo() {
        BankTransaction tx = tx();
        tx.setUploadId(UUID.randomUUID());

        assertThat(tx.importSource()).isEqualTo(BankTransaction.ImportSource.FILE);
    }

    @Test
    @DisplayName("Com os dois, a CONEXÃO vence: o upload ali é lote de gravação")
    void conexaoVenceUpload() {
        // A sincronização também grava um lote; o lote não é a procedência do
        // dado, é só o caminho pelo qual ele foi persistido
        BankTransaction tx = tx();
        tx.setAccountId(UUID.randomUUID());
        tx.setUploadId(UUID.randomUUID());

        assertThat(tx.importSource()).isEqualTo(BankTransaction.ImportSource.CONNECTION);
    }

    @Test
    @DisplayName("Sem nenhum dos dois, a resposta é 'não sei' — e isso não é falha")
    void semNenhumEDesconhecido() {
        // Histórico anterior ao EC-113. Dizer "não sei de onde veio" é honesto;
        // atribuir uma procedência que ninguém registrou, não
        assertThat(tx().importSource()).isEqualTo(BankTransaction.ImportSource.UNKNOWN);
    }

    @Test
    @DisplayName("NENHUMA linha fica sem resposta — a prova do EC-195")
    void nenhumaLinhaSemOrigem() {
        UUID algum = UUID.randomUUID();
        for (UUID conta : new UUID[] {null, algum}) {
            for (UUID upload : new UUID[] {null, algum}) {
                BankTransaction tx = tx();
                tx.setAccountId(conta);
                tx.setUploadId(upload);

                assertThat(tx.importSource())
                        .as("conta=%s upload=%s", conta, upload)
                        .isNotNull();
            }
        }
    }

    @Test
    @DisplayName("A resposta carrega a hora da IMPORTAÇÃO, que não é a data da compra")
    void horaDaImportacaoEOutraCoisa() {
        // A compra foi no dia 3; o arquivo entrou no dia 20. Quando o número da
        // tela não bate com o do banco, a distância entre as duas datas é quase
        // sempre a explicação
        BankTransaction tx = tx();
        tx.setDate(OffsetDateTime.parse("2026-09-03T10:00:00Z"));
        tx.setCreatedAt(OffsetDateTime.parse("2026-09-20T21:30:00Z"));

        BankTransactionResponse resposta = BankTransactionResponse.from(tx);

        assertThat(resposta.date()).isEqualTo(OffsetDateTime.parse("2026-09-03T10:00:00Z"));
        assertThat(resposta.importedAt()).isEqualTo(OffsetDateTime.parse("2026-09-20T21:30:00Z"));
        assertThat(resposta.source()).isEqualTo(BankTransaction.ImportSource.UNKNOWN);
    }

    @Test
    @DisplayName("O arquivo vira nome, formato, contagem e data")
    void arquivoViraRotulo() {
        StatementUpload upload = StatementUpload.builder()
                .id(UUID.randomUUID())
                .user(DONO)
                .fileHash("abc")
                .fileName("Extrato-12-08-2024-a-11-08-2026-CSV.csv")
                .format("CSV")
                .transactionsImported(1682)
                .createdAt(OffsetDateTime.parse("2026-09-08T14:02:00Z"))
                .build();

        ImportSourceResponse resposta = ImportSourceResponse.from(upload);

        assertThat(resposta.fileName()).isEqualTo("Extrato-12-08-2024-a-11-08-2026-CSV.csv");
        assertThat(resposta.format()).isEqualTo("CSV");
        assertThat(resposta.importedCount()).isEqualTo(1682);
        assertThat(resposta.importedAt()).isEqualTo(OffsetDateTime.parse("2026-09-08T14:02:00Z"));
    }

    private BankTransaction tx() {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(DONO)
                .transactionId("t1")
                .type("DEBIT")
                .amount(new BigDecimal("-50.00"))
                .description("Compra no débito Padaria")
                .date(OffsetDateTime.parse("2026-09-03T10:00:00Z"))
                .build();
    }
}
