package br.com.economize.service;

import br.com.economize.dto.account.AccountMergeSuggestion;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.InvoiceReserve;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.ConnectorAccountRepository;
import br.com.economize.repository.InvoiceReserveRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A fusão de origens duplicadas.
 *
 * <p><b>Medido na conta do dono em 16/09/2026</b>, depois de ele relatar que
 * "os números parecem estar meio embaralhados": a conta do Inter existia duas
 * vezes — uma solta, criada pelos arquivos, com <b>1.632 dos 1.967
 * lançamentos</b> e nenhum saldo; e uma ligada, do conector, com 75 lançamentos
 * e o saldo de R$ 250,00. Mercado Pago tinha três origens; Nubank, duas.
 *
 * <p>Os testes guardam as duas metades da decisão: sugerir só quando dá para
 * afirmar (dígitos finais e tipo iguais, uma solta e uma ligada só), e mover
 * ANTES de apagar — porque a chave estrangeira do extrato é
 * {@code SET NULL} e a das reservas é {@code CASCADE}.
 */
@ExtendWith(MockitoExtension.class)
class AccountMergeServiceTest {

    private static final String EMAIL = "dono@economize.app";

    @Mock
    private ConnectorAccountRepository accountRepository;
    @Mock
    private BankTransactionRepository transactionRepository;
    @Mock
    private InvoiceReserveRepository reserveRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AccountMergeService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").password("x").build();
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    // ------------------------------------------------- sugestões

    @Test
    @DisplayName("o par do dono: 'Inter ····2750' solta e 'BANCO INTER ····2750' ligada")
    void suggereOParComOsMesmosDigitos() {
        ConnectorAccount solta = conta("Inter ····2750", "Inter", null);
        ConnectorAccount ligada = conta("BANCO INTER ····2750", "MeuPluggy", UUID.randomUUID());
        when(accountRepository.findAllByUserIdOrderByNameAsc(user.getId()))
                .thenReturn(List.of(solta, ligada));
        when(transactionRepository.countByUserIdAndAccountId(user.getId(), solta.getId())).thenReturn(1632L);
        when(transactionRepository.countByUserIdAndAccountId(user.getId(), ligada.getId())).thenReturn(75L);

        List<AccountMergeSuggestion> sugestoes = service.suggestionsFor(EMAIL);

        assertThat(sugestoes).hasSize(1);
        AccountMergeSuggestion s = sugestoes.get(0);
        assertThat(s.digits()).isEqualTo("2750");
        // A solta é sempre a que desaparece: a ligada é quem sincroniza
        assertThat(s.sourceId()).isEqualTo(solta.getId());
        assertThat(s.targetId()).isEqualTo(ligada.getId());
        assertThat(s.sourceTransactions()).isEqualTo(1632L);
        assertThat(s.targetTransactions()).isEqualTo(75L);
    }

    /**
     * Cartão e conta corrente do mesmo banco terminam nos mesmos dígitos com
     * frequência. Juntá-los jogaria compras de cartão dentro da conta corrente.
     */
    @Test
    @DisplayName("tipos diferentes não formam par, mesmo com os dígitos iguais")
    void naoSuggereEntreTiposDiferentes() {
        ConnectorAccount solta = conta("Nubank ····0777", "Nubank", null);
        ConnectorAccount ligada = cartao("Nubank cartão ····0777", UUID.randomUUID());
        when(accountRepository.findAllByUserIdOrderByNameAsc(user.getId()))
                .thenReturn(List.of(solta, ligada));

        assertThat(service.suggestionsFor(EMAIL)).isEmpty();
    }

    /**
     * Duas ligadas com os mesmos dígitos é ambiguidade de verdade — e adivinhar
     * aqui mistura o extrato de duas contas. Não sugerir é a resposta certa.
     */
    @Test
    @DisplayName("duas candidatas ligadas: não sugere nada")
    void naoSuggereComAmbiguidade() {
        ConnectorAccount solta = conta("Inter ····2750", "Inter", null);
        when(accountRepository.findAllByUserIdOrderByNameAsc(user.getId()))
                .thenReturn(List.of(solta,
                        conta("BANCO INTER ····2750", "MeuPluggy", UUID.randomUUID()),
                        conta("INTER PJ ····2750", "MeuPluggy", UUID.randomUUID())));

        assertThat(service.suggestionsFor(EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("rótulo sem dígito nenhum fica de fora — não há o que afirmar")
    void naoSuggereSemDigitos() {
        when(accountRepository.findAllByUserIdOrderByNameAsc(user.getId()))
                .thenReturn(List.of(
                        conta("Flash · Vale refeição", "Flash", null),
                        conta("Flash", "MeuPluggy", UUID.randomUUID())));

        assertThat(service.suggestionsFor(EMAIL)).isEmpty();
    }

    /** Um rótulo traz o número inteiro e o outro só o fim: casam pelos 4 últimos. */
    @Test
    @DisplayName("número inteiro casa com os últimos quatro dígitos")
    void digitsOfPegaOsQuatroUltimos() {
        assertThat(AccountMergeService.digitsOf("5555777788880777")).isEqualTo("0777");
        assertThat(AccountMergeService.digitsOf("Nubank ····0777")).isEqualTo("0777");
        assertThat(AccountMergeService.digitsOf("Mercado Pago (Conta Pré-paga)")).isNull();
        assertThat(AccountMergeService.digitsOf(null)).isNull();
    }

    // ------------------------------------------------- fusão

    @Test
    @DisplayName("move os lançamentos ANTES de apagar a origem")
    void mergeMoveDepoisApaga() {
        ConnectorAccount solta = conta("Inter ····2750", "Inter", null);
        ConnectorAccount ligada = conta("BANCO INTER ····2750", "MeuPluggy", UUID.randomUUID());
        owned(solta);
        owned(ligada);
        when(transactionRepository.moveAccount(user.getId(), solta.getId(), ligada.getId())).thenReturn(1632);
        when(reserveRepository.findAllByUserIdAndCardAccountId(user.getId(), solta.getId()))
                .thenReturn(List.of());

        int movidos = service.merge(EMAIL, solta.getId(), ligada.getId());

        assertThat(movidos).isEqualTo(1632);
        // A ordem é o ponto: a FK do extrato é SET NULL, e apagar antes
        // transformaria 1.632 linhas em linhas sem origem
        var ordem = org.mockito.InOrder.class.cast(org.mockito.Mockito.inOrder(transactionRepository, accountRepository));
        ordem.verify(transactionRepository).moveAccount(user.getId(), solta.getId(), ligada.getId());
        ordem.verify(accountRepository).delete(solta);
    }

    /**
     * Reserva de fatura é dado DIGITADO pelo usuário, e a FK dela é CASCADE:
     * apagar a origem sem mover apaga a reserva em silêncio.
     */
    @Test
    @DisplayName("as reservas de fatura mudam de conta em vez de sumir na cascata")
    void mergeMoveAsReservas() {
        ConnectorAccount solta = cartao("Mercado Pago ····1311", null);
        ConnectorAccount ligada = cartao("Mercado Pago cartão ····1311", UUID.randomUUID());
        owned(solta);
        owned(ligada);
        when(transactionRepository.moveAccount(any(), any(), any())).thenReturn(2);
        InvoiceReserve reserva = InvoiceReserve.builder()
                .id(UUID.randomUUID()).user(user).cardAccount(solta)
                .reference("2026-09").amount(new BigDecimal("300.00")).build();
        when(reserveRepository.findAllByUserIdAndCardAccountId(user.getId(), solta.getId()))
                .thenReturn(List.of(reserva));
        when(reserveRepository.findByUserIdAndCardAccountIdAndReference(
                user.getId(), ligada.getId(), "2026-09")).thenReturn(Optional.empty());

        service.merge(EMAIL, solta.getId(), ligada.getId());

        assertThat(reserva.getCardAccount()).isEqualTo(ligada);
        verify(reserveRepository).save(reserva);
        verify(reserveRepository, never()).delete(any());
    }

    /** Reserva repetida para o mesmo ciclo: a do destino é a que vale. */
    @Test
    @DisplayName("reserva do mesmo ciclo nas duas contas: fica a do destino")
    void mergeDescartaReservaRepetida() {
        ConnectorAccount solta = cartao("Mercado Pago ····1311", null);
        ConnectorAccount ligada = cartao("Mercado Pago cartão ····1311", UUID.randomUUID());
        owned(solta);
        owned(ligada);
        when(transactionRepository.moveAccount(any(), any(), any())).thenReturn(0);
        InvoiceReserve repetida = InvoiceReserve.builder()
                .id(UUID.randomUUID()).user(user).cardAccount(solta)
                .reference("2026-09").amount(new BigDecimal("10.00")).build();
        when(reserveRepository.findAllByUserIdAndCardAccountId(user.getId(), solta.getId()))
                .thenReturn(List.of(repetida));
        when(reserveRepository.findByUserIdAndCardAccountIdAndReference(
                user.getId(), ligada.getId(), "2026-09"))
                .thenReturn(Optional.of(InvoiceReserve.builder().id(UUID.randomUUID()).build()));

        service.merge(EMAIL, solta.getId(), ligada.getId());

        verify(reserveRepository).delete(repetida);
        verify(reserveRepository, never()).save(any());
    }

    /**
     * Apagar a origem LIGADA é o pior caso: o conector a recriaria na leitura
     * seguinte e a duplicata voltaria no dia seguinte, agora com o histórico
     * na conta errada.
     */
    @Test
    @DisplayName("a conta que desaparece nunca pode ser a ligada")
    void mergeRecusaApagarALigada() {
        ConnectorAccount ligada = conta("BANCO INTER ····2750", "MeuPluggy", UUID.randomUUID());
        ConnectorAccount solta = conta("Inter ····2750", "Inter", null);
        owned(ligada);
        owned(solta);

        assertThatThrownBy(() -> service.merge(EMAIL, ligada.getId(), solta.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desvinculada");
        verify(accountRepository, never()).delete(any());
    }

    @Test
    @DisplayName("tipos diferentes são recusados")
    void mergeRecusaTiposDiferentes() {
        ConnectorAccount solta = conta("Nubank ····0777", "Nubank", null);
        ConnectorAccount ligada = cartao("Nubank cartão ····0777", UUID.randomUUID());
        owned(solta);
        owned(ligada);

        assertThatThrownBy(() -> service.merge(EMAIL, solta.getId(), ligada.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mesmo tipo");
        verify(transactionRepository, never()).moveAccount(any(), any(), any());
    }

    @Test
    @DisplayName("juntar a conta com ela mesma é recusado antes de qualquer escrita")
    void mergeRecusaAutoReferencia() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.merge(EMAIL, id, id))
                .isInstanceOf(IllegalArgumentException.class);
        verify(accountRepository, never()).delete(any());
    }

    // ------------------------------------------------- fixtures

    private ConnectorAccount conta(String nome, String instituicao, UUID pluggyItemId) {
        return ConnectorAccount.builder()
                .id(UUID.randomUUID()).user(user)
                .providerAccountId("prov-" + nome.hashCode())
                .name(nome).institution(instituicao)
                .type(ConnectorAccount.AccountType.BANK)
                .pluggyItemId(pluggyItemId)
                .build();
    }

    private ConnectorAccount cartao(String nome, UUID pluggyItemId) {
        return ConnectorAccount.builder()
                .id(UUID.randomUUID()).user(user)
                .providerAccountId("prov-" + nome.hashCode())
                .name(nome).institution("MeuPluggy")
                .type(ConnectorAccount.AccountType.CREDIT_CARD)
                .pluggyItemId(pluggyItemId)
                .build();
    }

    private void owned(ConnectorAccount account) {
        when(accountRepository.findByIdAndUserId(eq(account.getId()), eq(user.getId())))
                .thenReturn(Optional.of(account));
    }
}
