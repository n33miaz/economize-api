package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.FamilyGroup;
import br.com.economize.model.FamilyMember;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.FamilyGroupRepository;
import br.com.economize.repository.FamilyMemberRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.family.FamilyTransferService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A faxina de depois da importação lê só o que entrou — e decide o mesmo.
 *
 * <p>Cada varredura enquadrada tem aqui as duas provas que o recorte precisa:
 * <ol>
 *   <li>para as linhas importadas, o resultado é o da varredura total;</li>
 *   <li>o que está fora do recorte não é marcado <b>nem carregado</b> — a
 *       contagem é a de entidades que o Hibernate materializou, não a do que
 *       o serviço diz ter examinado.</li>
 * </ol>
 *
 * <p><b>Contra banco</b>, pelo mesmo motivo de
 * {@link BalanceReconciliationServiceTest}: o recorte mora na consulta. Com o
 * repositório dublado, o teste provaria que o serviço pede uma janela, não que
 * a janela traz as linhas certas.
 *
 * <p>O cenário é sempre o mesmo: um extrato de 2026 e uma importação que
 * trouxe os dias 10 a 12 de junho.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@Import({InternalTransferService.class, InvestmentFlowService.class,
        FamilyTransferService.class, DuplicateTransactionService.class})
class StatementHygieneWindowTest {

    private static final String EMAIL = "dono@economize.test";
    private static final OffsetDateTime DIA_10 = OffsetDateTime.parse("2026-06-10T09:00:00Z");
    private static final OffsetDateTime DIA_12 = OffsetDateTime.parse("2026-06-12T18:00:00Z");
    /** O que a importação trouxe: de 10 a 12 de junho. */
    private static final HygieneWindow JANELA = new HygieneWindow(DIA_10, DIA_12);
    /** Longe de qualquer margem, antes e depois. */
    private static final OffsetDateTime MARCO = OffsetDateTime.parse("2026-03-05T12:00:00Z");
    private static final OffsetDateTime SETEMBRO = OffsetDateTime.parse("2026-09-20T12:00:00Z");

    @Autowired
    private InternalTransferService internalTransferService;

    @Autowired
    private InvestmentFlowService investmentFlowService;

    @Autowired
    private FamilyTransferService familyTransferService;

    @Autowired
    private DuplicateTransactionService duplicateService;

    @Autowired
    private BankTransactionRepository bankTransactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FamilyGroupRepository groupRepository;

    @Autowired
    private FamilyMemberRepository memberRepository;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .name("Neemias Cormino Manso").email(EMAIL).password("x").build());
    }

    // ------------------------------------------------- movimentação própria

    @Test
    @DisplayName("Movimentação própria: marca o Pix importado, não lê o de março")
    void movimentacaoPropriaSoNaJanela() {
        BankTransaction antigo = tx("-300.00", "Pix enviado - Neemias Cormino Manso", MARCO, null);
        BankTransaction importado = tx("-150.00", "Pix enviado - Neemias Cormino Manso", DIA_10, null);
        BankTransaction ultimoDia = tx("150.00", "Pix recebido - Neemias Cormino Manso", DIA_12, null);
        tx("-40.00", "Compra no debito - Padaria", DIA_10.plusDays(1), null);
        BankTransaction futuro = tx("-80.00", "Pix enviado - Neemias Cormino Manso", SETEMBRO, null);

        Statistics estatisticas = zerar();
        InternalTransferService.Outcome naJanela =
                internalTransferService.reconcileByOwnName(EMAIL, JANELA);

        assertThat(naJanela.markedIds())
                .containsExactlyInAnyOrder(importado.getId(), ultimoDia.getId());
        // três linhas nos dias importados; as de março e setembro não vieram
        assertThat(carregadas(estatisticas)).isEqualTo(3);
        assertThat(recarregar(antigo).isInternalTransfer()).isFalse();
        assertThat(recarregar(futuro).isInternalTransfer()).isFalse();

        // A varredura total, depois, só acha o que ficou fora: para o que foi
        // importado ela não tem nada a acrescentar nem a corrigir
        limpar();
        InternalTransferService.Outcome total = internalTransferService.reconcileByOwnName(EMAIL);
        assertThat(total.markedIds()).containsExactlyInAnyOrder(antigo.getId(), futuro.getId());
    }

    // --------------------------------------------------- aplicação e resgate

    @Test
    @DisplayName("Aplicação e resgate: mesmo relatório da total para o que foi importado")
    void investimentoSoNaJanela() {
        BankTransaction antiga = tx("-400.00", "Aplicacao CDB", MARCO, null);
        BankTransaction aplicacao = tx("-500.00", "Aplicacao CDB", DIA_10, null);
        BankTransaction resgate = tx("120.00", "Resgate CDB", DIA_12, null);
        // fala de investimento, mas é imposto: fica na soma nos dois caminhos
        tx("-3.10", "Imposto IR/IOF - Tesouro direto", DIA_12, null);

        limpar();
        InvestmentFlowService.Outcome ensaioTotal = investmentFlowService.sweep(EMAIL, true);
        Set<UUID> importadas = Set.of(aplicacao.getId(), resgate.getId());
        List<InvestmentFlowService.Move> esperado = ensaioTotal.details().stream()
                .filter(m -> importadas.contains(m.transactionId())).toList();
        assertThat(ensaioTotal.marked()).isEqualTo(3);

        Statistics estatisticas = zerar();
        InvestmentFlowService.Outcome naJanela = investmentFlowService.sweep(EMAIL, false, JANELA);

        assertThat(naJanela.details()).containsExactlyInAnyOrderElementsOf(esperado);
        assertThat(naJanela.applied()).isEqualByComparingTo("500.00");
        assertThat(naJanela.redeemed()).isEqualByComparingTo("120.00");
        assertThat(carregadas(estatisticas)).isEqualTo(3);
        assertThat(recarregar(aplicacao).isInternalTransfer()).isTrue();
        assertThat(recarregar(antiga).isInternalTransfer()).isFalse();
    }

    // ------------------------------------------------- transferência da casa

    @Test
    @DisplayName("Casa: marca o Pix importado para a esposa, não lê o de março")
    void casaSoNaJanela() {
        User esposa = userRepository.save(User.builder()
                .name("Alice dos Santos Araujo").email("esposa@economize.test").password("x").build());
        FamilyGroup casa = groupRepository.save(FamilyGroup.builder()
                .name("Casa").owner(user).build());
        memberRepository.save(FamilyMember.builder()
                .group(casa).user(user).role(FamilyMember.Role.OWNER)
                .shareScope(FamilyMember.ShareScope.TRANSACTIONS).build());
        memberRepository.save(FamilyMember.builder()
                .group(casa).user(esposa).role(FamilyMember.Role.MEMBER)
                .shareScope(FamilyMember.ShareScope.TRANSACTIONS).build());

        BankTransaction antigo = tx("-650.00", "Pix enviado - Alice dos Santos Araujo", MARCO, null);
        BankTransaction importado = tx("-387.11", "Pix enviado - Alice dos Santos Araujo", DIA_10, null);
        tx("-92.40", "Compra no debito - Supermercado Araujo", DIA_12, null);

        Statistics estatisticas = zerar();
        FamilyTransferService.Outcome naJanela = familyTransferService.reconcile(EMAIL, JANELA);

        assertThat(naJanela.markedIds()).containsExactly(importado.getId());
        assertThat(carregadas(estatisticas)).isEqualTo(2);
        assertThat(recarregar(antigo).isFamilyTransfer()).isFalse();

        limpar();
        FamilyTransferService.Outcome total = familyTransferService.reconcile(EMAIL);
        assertThat(total.markedIds()).containsExactly(antigo.getId());
    }

    // ------------------------------------------------------------ duplicatas

    @Test
    @DisplayName("Duplicatas: os pares do que foi importado são os da total, com a outra ponta na borda")
    void duplicatasNaJanelaComMargem() {
        UUID conta = UUID.randomUUID();
        // Par antigo e JÁ varrido: é o estado em que a faxina encontra o
        // histórico, e é do que a igualdade com a total depende
        tx("-70.00", "Pix enviado: Cp :1-PADARIA CENTRAL", MARCO, conta);
        BankTransaction arquivoAntigo = tx("-70.00", "Pix enviado - Padaria Central", MARCO, null);
        limpar();
        assertThat(duplicateService.sweep(EMAIL, false).pairs()).isEqualTo(1);

        // A importação: uma linha de arquivo no dia 10 e uma da conexão no 12
        BankTransaction arquivoNovo = tx("-320.57", "Pix enviado - Pushinpay Pagamentos",
                DIA_10.withHour(0).withMinute(5), null);
        BankTransaction conexaoNova = tx("-45.90", "Compra no debito: MERCADO BOM PRECO",
                DIA_12, conta);
        // As outras pontas, já no extrato, EXATAMENTE no limite da regra: um
        // dia antes do primeiro dia importado (24 h e 5 min, o caso medido em
        // produção) e um dia depois do último
        BankTransaction conexaoVespera = tx("-320.5700", "Pix enviado: Cp :54811417-PUSHINPAY PAGAMENTOS",
                DIA_10.minusDays(1).withHour(0).withMinute(0), conta);
        BankTransaction arquivoDiaSeguinte = tx("-45.90", "Compra no debito - Mercado Bom Preco",
                DIA_12.plusDays(1).withHour(23), null);
        // Mesmo valor e mesma descrição a DOIS dias: a regra não pareia, e a
        // total também não
        tx("-45.90", "Compra no debito - Mercado Bom Preco", DIA_12.plusDays(2), null);
        // Par por fazer em setembro, fora de qualquer margem: é da faxina
        // pedida à mão, não desta
        tx("-18.00", "Pix enviado: Cp :9-BANCA DO ZE", SETEMBRO, conta);
        BankTransaction arquivoSetembro = tx("-18.00", "Pix enviado - Banca do Ze", SETEMBRO, null);

        limpar();
        DuplicateTransactionService.Outcome ensaioTotal = duplicateService.sweep(EMAIL, true);
        Set<UUID> importadas = Set.of(arquivoNovo.getId(), conexaoNova.getId());
        List<DuplicateTransactionService.Pair> esperado = ensaioTotal.details().stream()
                .filter(p -> importadas.contains(p.kept()) || importadas.contains(p.ignoredId()))
                .toList();
        assertThat(esperado).hasSize(2);
        assertThat(ensaioTotal.pairs()).isEqualTo(3);

        Statistics estatisticas = zerar();
        DuplicateTransactionService.Outcome naJanela = duplicateService.sweep(EMAIL, false, JANELA);

        assertThat(naJanela.details()).containsExactlyInAnyOrderElementsOf(esperado);
        assertThat(naJanela.details().stream().map(p -> Set.of(p.kept(), p.ignoredId()))
                .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        Set.of(conexaoVespera.getId(), arquivoNovo.getId()),
                        Set.of(conexaoNova.getId(), arquivoDiaSeguinte.getId()));
        // As quatro pontas e a linha a dois dias (que cai na folga do recorte
        // e é recusada pela regra). Março e setembro não foram lidos
        assertThat(carregadas(estatisticas)).isEqualTo(5);
        assertThat(recarregar(arquivoNovo).isIgnored()).isTrue();
        assertThat(recarregar(arquivoDiaSeguinte).isIgnored()).isTrue();
        assertThat(recarregar(arquivoSetembro).isIgnored()).isFalse();
        assertThat(recarregar(arquivoAntigo).getIgnoredReason())
                .isEqualTo(BankTransaction.IgnoredReason.DUPLICATE);

        // O que ficou para a faxina pedida à mão é só o par de setembro
        limpar();
        DuplicateTransactionService.Outcome total = duplicateService.sweep(EMAIL, false);
        assertThat(total.discardedIds()).containsExactly(arquivoSetembro.getId());
    }

    @Test
    @DisplayName("Duplicatas: a outra ponta um dia ALÉM do limite não pareia em nenhum dos dois caminhos")
    void duplicataAlemDoLimiteNaoPareia() {
        UUID conta = UUID.randomUUID();
        tx("-99.90", "Compra no debito - Livraria Cultura", DIA_10, null);
        tx("-99.90", "Compra no debito: LIVRARIA CULTURA", DIA_10.minusDays(2), conta);

        limpar();
        assertThat(duplicateService.sweep(EMAIL, true).pairs()).isZero();
        limpar();
        assertThat(duplicateService.sweep(EMAIL, false, JANELA).pairs()).isZero();
    }

    // ----------------------------------------------------------------- apoio

    /** Grava o pendente e esvazia o contexto: o serviço tem de ler do BANCO. */
    private void limpar() {
        em.flush();
        em.clear();
    }

    private Statistics zerar() {
        limpar();
        Statistics estatisticas = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        estatisticas.clear();
        return estatisticas;
    }

    /** Quantos lançamentos o Hibernate materializou desde {@link #zerar()}. */
    private long carregadas(Statistics estatisticas) {
        return estatisticas.getEntityStatistics(BankTransaction.class.getName()).getLoadCount();
    }

    /** O estado gravado, e não o objeto que o teste ainda segura. */
    private BankTransaction recarregar(BankTransaction tx) {
        limpar();
        return bankTransactionRepository.findById(tx.getId()).orElseThrow();
    }

    private BankTransaction tx(String valor, String descricao, OffsetDateTime quando, UUID contaId) {
        return bankTransactionRepository.save(BankTransaction.builder()
                .user(user)
                .transactionId("ext-" + UUID.randomUUID())
                .type(new BigDecimal(valor).signum() < 0 ? "DEBIT" : "CREDIT")
                .accountId(contaId)
                .amount(new BigDecimal(valor))
                .description(descricao)
                .date(quando)
                .internalTransfer(false)
                .ignored(false)
                .familyTransfer(false)
                .build());
    }
}
