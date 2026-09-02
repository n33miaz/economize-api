package br.com.economize.repository;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * As consultas rodando de verdade (EC-117).
 *
 * <p>Até aqui todo teste da API dublava o repositório: nenhuma cláusula de dono
 * e nenhum agregado tinha sido executado por banco algum. O que se testava era
 * o serviço acreditando na consulta — nunca a consulta.
 *
 * <p>O alvo é o que uma consulta errada faz de pior: <b>devolver linha de outro
 * usuário</b>. Foi assim que nasceu o IDOR do EC-037, e é o único erro desta
 * camada que nenhum teste de serviço pode pegar, porque o serviço só vê o que o
 * repositório entrega.
 *
 * <p>H2, e não Postgres: as consultas são JPQL puro exatamente para valer nos
 * dois, e o casamento entre entidade e migration é provado por outro caminho —
 * o ensaio com {@code ddl-auto=validate} contra Postgres real.
 */
@DataJpaTest
@DisplayName("BankTransactionRepository contra banco (EC-117)")
class BankTransactionRepositoryTest {

    private static final OffsetDateTime JULHO_10 =
            OffsetDateTime.of(2026, 7, 10, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private BankTransactionRepository repository;

    @Autowired
    private UserRepository userRepository;

    /**
     * Só para LER de volta o que um {@code @Modifying} escreveu. Update em
     * massa é SQL direto: ele não atualiza a entidade que já está no contexto,
     * então ler sem limpar devolve o valor antigo e o teste passaria (ou
     * falharia) por motivo nenhum.
     */
    @Autowired
    private TestEntityManager em;

    private User dono;
    private User estranho;

    @BeforeEach
    void setUp() {
        dono = userRepository.save(user("dono@economize.test"));
        estranho = userRepository.save(user("estranho@economize.test"));
    }

    private User user(String email) {
        return User.builder()
                .name(email)
                .email(email)
                .password("nao-importa")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private BankTransaction transacao(User owner, String valor, OffsetDateTime quando) {
        return repository.save(BankTransaction.builder()
                .user(owner)
                .transactionId("ext-" + UUID.randomUUID())
                .type(new BigDecimal(valor).signum() < 0 ? "DEBIT" : "CREDIT")
                .amount(new BigDecimal(valor))
                .description("Lancamento")
                .date(quando)
                .reviewStatus(BankTransaction.ReviewStatus.CONFIRMED)
                .internalTransfer(false)
                .build());
    }

    // --------------------------------------------------- a cláusula de dono

    @Test
    @DisplayName("Id de outro usuário não devolve linha nenhuma")
    void findByIdAndUserIdIgnoraDonoErrado() {
        BankTransaction alheia = transacao(estranho, "-100.00", JULHO_10);

        Optional<BankTransaction> achada =
                repository.findByIdAndUserId(alheia.getId(), dono.getId());

        // A rota responde 404 com isto vazio. Se voltasse a linha, o app
        // exibiria dado de outra pessoa sem nenhum erro aparente
        assertThat(achada).isEmpty();
    }

    @Test
    @DisplayName("O próprio dono encontra a sua transação")
    void findByIdAndUserIdEncontraDoDono() {
        BankTransaction minha = transacao(dono, "-100.00", JULHO_10);

        assertThat(repository.findByIdAndUserId(minha.getId(), dono.getId()))
                .get()
                .extracting(BankTransaction::getId)
                .isEqualTo(minha.getId());
    }

    @Test
    @DisplayName("A listagem por dono não vaza a transação do vizinho")
    void listagemPorDonoNaoVaza() {
        transacao(dono, "-100.00", JULHO_10);
        transacao(estranho, "-999.00", JULHO_10);

        List<BankTransaction> minhas = repository.findAllByUserIdOrderByDateDesc(dono.getId());

        assertThat(minhas).hasSize(1);
        assertThat(minhas.get(0).getAmount()).isEqualByComparingTo("-100.00");
    }

    @Test
    @DisplayName("O id externo é único POR usuário, não global")
    void idExternoEUnicoPorUsuario() {
        BankTransaction minha = transacao(dono, "-100.00", JULHO_10);

        // Duas pessoas podem importar extrato do mesmo banco, e o FITID repete
        // entre elas: dedupe global recusaria a linha da segunda
        assertThat(repository.existsByUserIdAndTransactionId(
                dono.getId(), minha.getTransactionId())).isTrue();
        assertThat(repository.existsByUserIdAndTransactionId(
                estranho.getId(), minha.getTransactionId())).isFalse();
    }

    // ------------------------------------------------- o agregado da análise

    @Test
    @DisplayName("A soma por categoria ignora a perna interna (EC-106)")
    void somaIgnoraTransferenciaInterna() {
        transacao(dono, "-100.00", JULHO_10);
        BankTransaction interna = transacao(dono, "-500.00", JULHO_10);
        interna.setInternalTransfer(true);
        repository.saveAndFlush(interna);

        var totais = repository.sumByCategory(dono.getId(),
                JULHO_10.minusDays(9), JULHO_10.plusDays(1));

        // Pagar a fatura não é despesa nova: a despesa foi a compra. Sem o
        // filtro, conectar cartão e conta dobrava o gasto do mês
        assertThat(totais).hasSize(1);
        assertThat(totais.get(0).getTotal()).isEqualByComparingTo("-100.00");
    }

    @Test
    @DisplayName("A janela é fechada no início e ABERTA no fim")
    void janelaFechadaNoInicioAbertaNoFim() {
        OffsetDateTime inicio = OffsetDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime fimExclusivo = OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        transacao(dono, "-10.00", inicio);
        transacao(dono, "-20.00", fimExclusivo);

        var totais = repository.sumByCategory(dono.getId(), inicio, fimExclusivo);

        // O primeiro instante de agosto pertence a agosto. Contar os dois lados
        // faria o lançamento da virada ser somado em dois meses
        assertThat(totais).hasSize(1);
        assertThat(totais.get(0).getTotal()).isEqualByComparingTo("-10.00");
    }

    @Test
    @DisplayName("A soma não alcança o dado de outro usuário")
    void somaNaoAlcancaOutroUsuario() {
        transacao(estranho, "-777.00", JULHO_10);

        var totais = repository.sumByCategory(dono.getId(),
                JULHO_10.minusDays(1), JULHO_10.plusDays(1));

        assertThat(totais).isEmpty();
    }

    @Test
    @DisplayName("Crédito e débito somam em linhas separadas")
    void creditoEDebitoSeparados() {
        transacao(dono, "4400.00", JULHO_10);
        transacao(dono, "-3500.00", JULHO_10);

        var totais = repository.sumByCategory(dono.getId(),
                JULHO_10.minusDays(1), JULHO_10.plusDays(1));

        assertThat(totais).hasSize(2);
        assertThat(totais)
                .extracting(BankTransactionRepository.CategoryTotal::getType)
                .containsExactlyInAnyOrder("CREDIT", "DEBIT");
    }

    // --------------------------------------------------- limites e carimbo

    @Test
    @DisplayName("Os limites de data saem do próprio usuário")
    void limitesDeDataPorUsuario() {
        OffsetDateTime maio = OffsetDateTime.of(2026, 5, 3, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime julho = OffsetDateTime.of(2026, 7, 28, 0, 0, 0, 0, ZoneOffset.UTC);
        transacao(dono, "-10.00", maio);
        transacao(dono, "-20.00", julho);
        transacao(estranho, "-30.00",
                OffsetDateTime.of(2026, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC));

        List<Object[]> limites = repository.findDateBounds(dono.getId());

        assertThat(((OffsetDateTime) limites.get(0)[0]).toInstant()).isEqualTo(maio.toInstant());
        // Dezembro é do vizinho: entrar aqui esticaria o seletor de meses e a
        // data mais recente do EC-137 para um período sem dado do dono
        assertThat(((OffsetDateTime) limites.get(0)[1]).toInstant()).isEqualTo(julho.toInstant());
    }

    @Test
    @DisplayName("Sem transação, os limites vêm nulos em vez de linha nenhuma")
    void limitesSemTransacao() {
        List<Object[]> limites = repository.findDateBounds(dono.getId());

        // min/max sobre conjunto vazio devolve UMA linha de nulos, e é nisso
        // que o serviço se apoia para responder "não há mês nenhum"
        assertThat(limites).hasSize(1);
        assertThat(limites.get(0)[0]).isNull();
    }

    @Test
    @DisplayName("O carimbo de conta só alcança quem ainda não tem origem")
    void carimboDeContaNaoSobrescreve() {
        UUID contaNova = UUID.randomUUID();
        UUID contaAntiga = UUID.randomUUID();
        BankTransaction semOrigem = transacao(dono, "-10.00", JULHO_10);
        BankTransaction jaCarimbada = transacao(dono, "-20.00", JULHO_10);
        jaCarimbada.setAccountId(contaAntiga);
        repository.saveAndFlush(jaCarimbada);

        int alteradas = repository.assignAccount(dono.getId(), contaNova,
                List.of(semOrigem.getId(), jaCarimbada.getId()));
        em.clear();

        assertThat(alteradas).isEqualTo(1);
        assertThat(repository.findById(jaCarimbada.getId()).orElseThrow().getAccountId())
                .isEqualTo(contaAntiga);
        assertThat(repository.findById(semOrigem.getId()).orElseThrow().getAccountId())
                .isEqualTo(contaNova);
    }

    @Test
    @DisplayName("O carimbo não atravessa para a transação de outro usuário")
    void carimboRespeitaODono() {
        UUID conta = UUID.randomUUID();
        BankTransaction alheia = transacao(estranho, "-10.00", JULHO_10);

        int alteradas = repository.assignAccount(dono.getId(), conta, List.of(alheia.getId()));
        em.clear();

        assertThat(alteradas).isZero();
        assertThat(repository.findById(alheia.getId()).orElseThrow().getAccountId()).isNull();
    }
}
