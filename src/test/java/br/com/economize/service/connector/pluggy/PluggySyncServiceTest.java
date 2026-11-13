package br.com.economize.service.connector.pluggy;

import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.PluggyItem;
import br.com.economize.model.User;
import br.com.economize.repository.PluggyItemRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.BankStatementService;
import br.com.economize.service.ConnectorAccountService;
import br.com.economize.service.statement.parser.ParsedTransaction;
import br.com.economize.service.statement.parser.StatementFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluggySyncServiceTest {

    private static final String EMAIL = "teste@economize.app";

    @Mock
    private PluggyClient pluggyClient;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PluggyItemRepository pluggyItemRepository;

    @Mock
    private PluggyItemService pluggyItemService;

    @Mock
    private BankStatementService bankStatementService;

    @Mock
    private ConnectorAccountService accountService;

    @InjectMocks
    private PluggySyncService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Teste").password("x").build();
        // EC-113: toda conta percorrida vira uma origem registrada. O id devolvido
        // é o que cada linha carrega até a gravação, então aqui basta ser estável
        // por conta do provedor — é isso que os testes de origem conferem.
        lenient().when(accountService.register(eq(user), any(ConnectorAccountService.AccountSnapshot.class)))
                .thenAnswer(inv -> {
                    ConnectorAccountService.AccountSnapshot snapshot = inv.getArgument(1);
                    return ConnectorAccount.builder()
                            .id(ACCOUNT_IDS.computeIfAbsent(snapshot.providerAccountId(),
                                    key -> UUID.randomUUID()))
                            .user(user)
                            .providerAccountId(snapshot.providerAccountId())
                            .name(snapshot.name())
                            .institution(snapshot.institution())
                            .type(snapshot.type())
                            .statementClosingDay(snapshot.statementClosingDay())
                            .statementDueDay(snapshot.statementDueDay())
                            .pluggyItemId(snapshot.pluggyItemId())
                            .build();
                });
    }

    /** id interno estável por conta do provedor dentro de um mesmo teste. */
    private final Map<String, UUID> ACCOUNT_IDS = new java.util.HashMap<>();

    @Test
    @DisplayName("sync percorre TODOS os itens do usuário e inclui o cartão de crédito com o sinal espelhado")
    void syncShouldWalkAllUserItemsIncludingCreditCard() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        PluggyItem inter = PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build();
        PluggyItem nubank = PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-2").build();
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(inter, nubank));

        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK")));
        when(pluggyClient.accounts("api-key", "item-2")).thenReturn(List.of(
                Map.of("id", "acc-card", "type", "CREDIT"),
                // investimento não é extrato: fica fora do pipeline
                Map.of("id", "acc-inv", "type", "INVESTMENT")));

        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of(
                        "id", "b1", "amount", "-50.00", "date", "2026-08-10",
                        "description", "PIX MERCADO CENTRAL")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        // convenção do Pluggy no cartão: compra vem POSITIVA
                        Map.of("id", "c1", "amount", "100.00", "date", "2026-08-11",
                                "description", "IFOOD RESTAURANTE"),
                        // pagamento da fatura vem NEGATIVO no cartão
                        Map.of("id", "c2", "amount", "-500.00", "date", "2026-08-12",
                                "description", "Pagamento de fatura"),
                        // sem amount não há como classificar: linha é pulada
                        Map.of("id", "c3", "date", "2026-08-12", "description", "quebrada")));

        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 3, 1, 0, 0, false, "PLUGGY"));

        PluggySyncService.SyncResult sync = service.sync(EMAIL, 90);

        assertThat(sync.itemsSynced()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq(StatementFormat.PLUGGY),
                captor.capture(), anyMap());
        List<ParsedTransaction> parsed = captor.getValue();
        assertThat(parsed).hasSize(3);

        ParsedTransaction bankTx = byId(parsed, "PLUGGY-b1");
        assertThat(bankTx.getAmount()).isEqualByComparingTo("-50.00");
        assertThat(bankTx.getType()).isEqualTo("DEBIT");

        // gasto de cartão é débito: o sinal do Pluggy foi espelhado
        ParsedTransaction purchase = byId(parsed, "PLUGGY-c1");
        assertThat(purchase.getAmount()).isEqualByComparingTo("-100.00");
        assertThat(purchase.getType()).isEqualTo("DEBIT");
        // a descrição real fica intocada (âncora "fatura" da recorrência)
        assertThat(purchase.getDescription()).isEqualTo("IFOOD RESTAURANTE");

        assertThat(purchase.isInternalTransfer()).isFalse();

        ParsedTransaction payment = byId(parsed, "PLUGGY-c2");
        assertThat(payment.getAmount()).isEqualByComparingTo("500.00");
        assertThat(payment.getType()).isEqualTo("CREDIT");
        assertThat(payment.getDescription()).isEqualTo("Pagamento de fatura");
        // crédito DENTRO do cartão nunca é receita do titular: quita a fatura ou
        // estorna uma compra. Marcado, sai das somas de receita/despesa
        assertThat(payment.isInternalTransfer()).isTrue();

        // débito comum da conta corrente não é afetado
        assertThat(bankTx.isInternalTransfer()).isFalse();

        // conta de investimento nunca foi consultada
        verify(pluggyClient, never()).transactions(eq("api-key"), eq("acc-inv"), any(), any());

        // carimbo de última sync nos dois itens. Dois saves: a saúde logo após
        // conferir e o carimbo depois da importação
        assertThat(inter.getLastSyncedAt()).isNotNull();
        assertThat(nubank.getLastSyncedAt()).isNotNull();
        verify(pluggyItemRepository, org.mockito.Mockito.times(2)).saveAll(List.of(inter, nubank));
    }

    @Test
    @DisplayName("pagamento de fatura: as DUAS pernas viram transferência interna; só a COMPRA é despesa")
    void syncShouldNeutralizeBothLegsOfCardBillPayment() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        PluggyItem banco = PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build();
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(banco));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK"),
                Map.of("id", "acc-card", "type", "CREDIT")));

        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        // (1) a COMPRA — esta é a despesa do mês
                        Map.of("id", "c1", "amount", "500.00", "date", "2026-08-05",
                                "description", "IFOOD RESTAURANTE"),
                        // (3) o pagamento da fatura DENTRO do cartão
                        Map.of("id", "c2", "amount", "-500.00", "date", "2026-08-12",
                                "description", "Pagamento de fatura")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        // (2) o pagamento da fatura saindo da CONTA CORRENTE
                        Map.of("id", "b1", "amount", "-500.00", "date", "2026-08-12",
                                "description", "PAGAMENTO FATURA CARTAO"),
                        // despesa comum de mesmo valor, mas sem a âncora "fatura":
                        // não pode ser confundida com perna de pagamento
                        Map.of("id", "b2", "amount", "-500.00", "date", "2026-08-14",
                                "description", "ALUGUEL AGOSTO")));

        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 4, 2, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq(StatementFormat.PLUGGY),
                captor.capture(), anyMap());
        List<ParsedTransaction> parsed = captor.getValue();

        // (1) a compra é a única despesa de cartão que conta
        ParsedTransaction compra = byId(parsed, "PLUGGY-c1");
        assertThat(compra.getAmount()).isEqualByComparingTo("-500.00");
        assertThat(compra.getType()).isEqualTo("DEBIT");
        assertThat(compra.isInternalTransfer()).isFalse();

        // (3) perna do cartão: neutralizada — não é receita
        assertThat(byId(parsed, "PLUGGY-c2").isInternalTransfer()).isTrue();

        // (2) perna da conta corrente: neutralizada — a despesa já foi a compra
        assertThat(byId(parsed, "PLUGGY-b1").isInternalTransfer()).isTrue();

        // o aluguel de MESMO valor continua despesa: valor coincidente sozinho
        // não neutraliza nada, a âncora "fatura" também é exigida
        ParsedTransaction aluguel = byId(parsed, "PLUGGY-b2");
        assertThat(aluguel.getType()).isEqualTo("DEBIT");
        assertThat(aluguel.isInternalTransfer()).isFalse();

        // a descrição de todas segue intocada (âncora do ADR-015)
        assertThat(byId(parsed, "PLUGGY-b1").getDescription()).isEqualTo("PAGAMENTO FATURA CARTAO");
    }

    @Test
    @DisplayName("um crédito de cartão neutraliza NO MÁXIMO um débito: o excedente continua despesa")
    void cardCreditShouldNeutralizeAtMostOneDebit() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK"),
                Map.of("id", "acc-card", "type", "CREDIT")));

        // UM pagamento de fatura dentro do cartão
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "c1", "amount", "-500.00", "date", "2026-08-12",
                        "description", "Pagamento de fatura")));
        // DOIS débitos de fatura de mesmo valor na conta corrente: um deles é de
        // outro cartão, que o usuário não conectou — e continua sendo despesa
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        Map.of("id", "b1", "amount", "-500.00", "date", "2026-08-12",
                                "description", "PAGAMENTO FATURA CARTAO"),
                        Map.of("id", "b2", "amount", "-500.00", "date", "2026-08-12",
                                "description", "PAGAMENTO FATURA OUTRO BANCO")));

        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 3, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq(StatementFormat.PLUGGY),
                captor.capture(), anyMap());
        List<ParsedTransaction> parsed = captor.getValue();

        assertThat(byId(parsed, "PLUGGY-b1").isInternalTransfer()).isTrue();
        // sem multiplicidade, o mesmo crédito autorizava apagar os dois — e o mês
        // inteiro de despesas sumia em cascata a partir de uma contrapartida só
        assertThat(byId(parsed, "PLUGGY-b2").isInternalTransfer()).isFalse();
    }

    @Test
    @DisplayName("dois pagamentos de fatura no mês neutralizam dois débitos — a contagem é por par")
    void twoCardCreditsShouldNeutralizeTwoDebits() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK"),
                Map.of("id", "acc-card", "type", "CREDIT")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        Map.of("id", "c1", "amount", "-500.00", "date", "2026-08-12",
                                "description", "Pagamento de fatura"),
                        Map.of("id", "c2", "amount", "-500.00", "date", "2026-08-20",
                                "description", "Pagamento de fatura parcial")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        Map.of("id", "b1", "amount", "-500.00", "date", "2026-08-12",
                                "description", "PAGAMENTO FATURA CARTAO"),
                        Map.of("id", "b2", "amount", "-500.00", "date", "2026-08-20",
                                "description", "PAGAMENTO FATURA CARTAO")));
        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 4, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq(StatementFormat.PLUGGY),
                captor.capture(), anyMap());

        assertThat(byId(captor.getValue(), "PLUGGY-b1").isInternalTransfer()).isTrue();
        assertThat(byId(captor.getValue(), "PLUGGY-b2").isInternalTransfer()).isTrue();
    }

    @Test
    @DisplayName("estorno anunciado no cartão NÃO é pagamento de fatura: segue crédito comum, para abater a compra")
    void announcedRefundOnCardIsNotABillPayment() {
        stubCardAndBank(
                List.of(
                        Map.of("id", "c1", "amount", "100.00", "date", "2026-08-10",
                                "description", "IFOOD RESTAURANTE"),
                        // devolução da compra: o banco diz o que é na própria linha
                        Map.of("id", "c2", "amount", "-100.00", "date", "2026-08-12",
                                "description", "Estorno de \"IFOOD RESTAURANTE\""),
                        // mesma coisa com acento e caixa alta, como o Inter escreve
                        Map.of("id", "c3", "amount", "-18.50", "date", "2026-08-13",
                                "description", "DEVOLUÇÃO PARCIAL MERCADO")),
                List.of());

        List<ParsedTransaction> parsed = syncAndCapture();

        // Marcado como perna interna, o estorno aparecia na fatura como
        // "pagamento" (não abatia a dívida) e ficava FORA da conciliação de
        // estorno, que pula linha interna — a compra seguia inteira na despesa,
        // contra a decisão do dono de que estorno abate a compra (V40)
        assertThat(byId(parsed, "PLUGGY-c2").isInternalTransfer()).isFalse();
        assertThat(byId(parsed, "PLUGGY-c2").getAmount()).isEqualByComparingTo("100.00");
        assertThat(byId(parsed, "PLUGGY-c3").isInternalTransfer()).isFalse();
        assertThat(byId(parsed, "PLUGGY-c1").isInternalTransfer()).isFalse();
    }

    @Test
    @DisplayName("estorno anunciado no cartão já chega NEUTRO: crédito em cartão de crédito nunca é receita")
    void announcedCardRefundArrivesOutOfTheSums() {
        stubCardAndBank(
                List.of(
                        // "Estorno parcial" sem nome de loja: a conciliação não
                        // tem como achar a compra, e sem a marca este crédito
                        // entraria como RECEITA em todas as somas
                        Map.of("id", "c1", "amount", "-18.50", "date", "2026-08-12",
                                "description", "Estorno parcial"),
                        Map.of("id", "c2", "amount", "50.00", "date", "2026-08-12",
                                "description", "MERCADO")),
                // na CONTA CORRENTE o mesmo texto é dinheiro que voltou de
                // verdade: ali quem decide é a conciliação, com o par na mão
                List.of(Map.of("id", "b1", "amount", "18.50", "date", "2026-08-12",
                        "description", "Estorno compra no debito")));

        List<ParsedTransaction> parsed = syncAndCapture();

        assertThat(byId(parsed, "PLUGGY-c1").isRefunded()).isTrue();
        assertThat(byId(parsed, "PLUGGY-c1").isInternalTransfer()).isFalse();
        assertThat(byId(parsed, "PLUGGY-c2").isRefunded()).isFalse();
        assertThat(byId(parsed, "PLUGGY-b1").isRefunded()).isFalse();
    }

    @Test
    @DisplayName("estorno anunciado não serve de contrapartida: o débito de fatura de mesmo valor continua despesa")
    void announcedRefundDoesNotFeedThePool() {
        stubCardAndBank(
                List.of(Map.of("id", "c1", "amount", "-100.00", "date", "2026-08-12",
                        "description", "ESTORNO IFOOD RESTAURANTE")),
                List.of(Map.of("id", "b1", "amount", "-100.00", "date", "2026-08-12",
                        "description", "PAGAMENTO FATURA CARTAO")));

        List<ParsedTransaction> parsed = syncAndCapture();

        // Era a "limitação conhecida": o estorno entrava no conjunto e
        // neutralizava um pagamento de fatura de valor coincidente. Agora o
        // crédito que se anuncia como estorno não é oferecido — as duas linhas
        // ficam como são: uma devolução e uma saída de dinheiro
        assertThat(byId(parsed, "PLUGGY-c1").isInternalTransfer()).isFalse();
        assertThat(byId(parsed, "PLUGGY-b1").isInternalTransfer()).isFalse();
    }

    @Test
    @DisplayName("só o cartão conectado: pagamento de fatura sem contrapartida NÃO vira receita")
    void billPaymentWithoutConnectedBankIsNeverIncome() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        // a conta corrente que paga a fatura é de outro banco, não conectado
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-card", "type", "CREDIT")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        // o texto do Nubank: não tem a âncora "fatura" nem marca
                        // de estorno — pelo texto, não dá para saber o que é
                        Map.of("id", "c1", "amount", "-2500.00", "date", "2026-08-12",
                                "description", "Pagamento recebido"),
                        Map.of("id", "c2", "amount", "-2500.00", "date", "2026-09-12",
                                "description", "Pagamento de fatura")));
        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 2, 0, 0, 0, false, "PLUGGY"));

        List<ParsedTransaction> parsed = syncAndCapture();

        // A contrapartida não existe nesta sync e nunca vai existir. Se a falta
        // dela bastasse para a linha virar crédito comum, toda fatura paga
        // entraria como RECEITA do mês — milhares de reais, todo mês. O erro
        // escolhido é o outro: crédito de cartão que NÃO se anuncia como estorno
        // continua perna interna. O custo é o estorno sem marca no texto seguir
        // como hoje (lido como pagamento), que é raro e pequeno
        assertThat(byId(parsed, "PLUGGY-c1").isInternalTransfer()).isTrue();
        assertThat(byId(parsed, "PLUGGY-c2").isInternalTransfer()).isTrue();
    }

    @Test
    @DisplayName("banco conectado mas débito sem a âncora 'fatura': o pagamento no cartão segue fora da receita")
    void billPaymentWhoseBankLegHasNoAnchorIsNeverIncome() {
        stubCardAndBank(
                List.of(Map.of("id", "c1", "amount", "-2500.00", "date", "2026-08-12",
                        "description", "Pagamento recebido")),
                // é assim que o pagamento por Pix aparece na conta: sem "fatura"
                List.of(Map.of("id", "b1", "amount", "-2500.00", "date", "2026-08-12",
                        "description", "PIX ENVIADO NU PAGAMENTOS SA")));

        List<ParsedTransaction> parsed = syncAndCapture();

        // o par não fecha (a perna da conta exige a âncora), e mesmo assim o
        // crédito do cartão não pode virar receita
        assertThat(byId(parsed, "PLUGGY-c1").isInternalTransfer()).isTrue();
        assertThat(byId(parsed, "PLUGGY-b1").isInternalTransfer()).isFalse();
    }

    private void stubCardAndBank(List<Map<String, Object>> card, List<Map<String, Object>> bank) {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK"),
                Map.of("id", "acc-card", "type", "CREDIT")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(card);
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(bank);
        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 2, 0, 0, 0, false, "PLUGGY"));
    }

    private List<ParsedTransaction> syncAndCapture() {
        service.sync(EMAIL, 90);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq(StatementFormat.PLUGGY),
                captor.capture(), anyMap());
        return captor.getValue();
    }

    @Test
    @DisplayName("sem cartão conectado o pagamento de fatura da conta corrente CONTINUA despesa")
    void syncShouldKeepBillPaymentAsExpenseWithoutConnectedCard() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        PluggyItem banco = PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build();
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(banco));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "b1", "amount", "-500.00", "date", "2026-08-12",
                        "description", "PAGAMENTO FATURA CARTAO")));
        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 1, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq(StatementFormat.PLUGGY),
                captor.capture(), anyMap());

        // sem a compra na base, este débito é a ÚNICA representação do gasto do
        // cartão: neutralizá-lo faria as despesas do usuário sumirem sem nada
        // no lugar
        assertThat(byId(captor.getValue(), "PLUGGY-b1").isInternalTransfer()).isFalse();
    }

    @Test
    @DisplayName("lançamento PENDENTE fica de fora: ele volta efetivado com outro id e duplicaria")
    void syncShouldSkipPendingTransactions() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        PluggyItem cartao = PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build();
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(cartao));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-card", "type", "CREDIT")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        Map.of("id", "c1", "amount", "100.00", "date", "2026-08-11",
                                "description", "IFOOD", "status", "POSTED"),
                        Map.of("id", "c2", "amount", "80.00", "date", "2026-08-14",
                                "description", "UBER", "status", "PENDING")));
        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 1, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq(StatementFormat.PLUGGY),
                captor.capture(), anyMap());

        assertThat(captor.getValue()).extracting(ParsedTransaction::getExternalId)
                .containsExactly("PLUGGY-c1");
    }

    @Test
    @DisplayName("days fora da faixa responde 400 dizendo o limite, em vez de virar 1 dia em silêncio")
    void syncShouldRejectDaysOutOfRange() {
        assertThatThrownBy(() -> service.sync(EMAIL, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("days deve estar entre 1 e 400");

        assertThatThrownBy(() -> service.sync(EMAIL, 999999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("days deve estar entre 1 e 400");

        // nem o usuário é consultado: é validação de entrada pura, antes de I/O
        verify(userRepository, never()).findByEmail(anyString());
        verify(pluggyClient, never()).authenticate();
    }

    @Test
    @DisplayName("sync sem nenhuma conexão registrada orienta a conectar pelo app")
    void syncShouldFailWithoutItems() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.sync(EMAIL, 90))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nenhuma conexão Pluggy");

        verify(bankStatementService, never()).importFromConnector(any(), anyString(), any(), anyList());
    }

    @Test
    @DisplayName("sync sem credenciais da aplicação falha antes de chamar o Pluggy")
    void syncShouldFailWithoutCredentials() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.sync(EMAIL, 90))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLUGGY_CLIENT_ID");

        verify(pluggyClient, never()).authenticate();
    }

    @Test
    @DisplayName("sync semeia os itens de env do dono ANTES de listar as conexões")
    void syncShouldSeedEnvItemsBeforeListing() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        PluggyItem seeded = PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("env-1").build();
        // a listagem já enxerga o item recém-semeado: é a mesma sync que o migra
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(seeded));
        when(pluggyClient.accounts("api-key", "env-1")).thenReturn(List.of());
        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(null, 0, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 30);

        verify(pluggyItemService).seedFromEnv(user, "api-key");
    }

    @Test
    @DisplayName("status mantém o contrato do APK (enabled/owner/configured/itemCount) contando itens de env pendentes")
    void statusShouldKeepPublishedContract() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyItemRepository.countByUserId(user.getId())).thenReturn(1L);
        when(pluggyItemService.pendingEnvItems(EMAIL)).thenReturn(1L);

        Map<String, Object> status = service.status(EMAIL);

        assertThat(status).containsEntry("enabled", true)
                .containsEntry("owner", true)
                .containsEntry("configured", true)
                .containsEntry("itemCount", 2L);
    }

    @Test
    @DisplayName("status de usuário sem conexões responde configured=false")
    void statusShouldReportUnconfiguredWithoutItems() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyItemRepository.countByUserId(user.getId())).thenReturn(0L);
        when(pluggyItemService.pendingEnvItems(EMAIL)).thenReturn(0L);

        Map<String, Object> status = service.status(EMAIL);

        assertThat(status).containsEntry("configured", false)
                .containsEntry("itemCount", 0L);
    }

    // ------------------------------------------------- EC-113: origem da linha

    @Test
    @DisplayName("sync registra a ORIGEM de cada conta e carimba o id dela em toda linha importada")
    void syncShouldRecordOriginForEveryAccount() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        PluggyItem nubank = PluggyItem.builder().id(UUID.randomUUID()).user(user)
                .itemId("item-1").connectorName("Nubank").build();
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(nubank));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK", "name", "Conta Corrente", "number", "12345-6"),
                Map.of("id", "acc-card", "type", "CREDIT", "name", "Mastercard Black",
                        "marketingName", "Ultravioleta", "number", "1234",
                        "creditData", Map.of("balanceCloseDate", "2026-08-10",
                                "balanceDueDate", "2026-08-17",
                                // o limite total vem no mesmo bloco; o disponível
                                // é limite menos devido e fica de fora
                                "creditLimit", "5330.01", "availableCreditLimit", "3992.06"))));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "b1", "amount", "-50.00", "date", "2026-08-10",
                        "description", "SUPERMERCADO")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "c1", "amount", "100.00", "date", "2026-08-11",
                        "description", "IFOOD RESTAURANTE")));
        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 2, 1, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        ArgumentCaptor<ConnectorAccountService.AccountSnapshot> snapshots =
                ArgumentCaptor.forClass(ConnectorAccountService.AccountSnapshot.class);
        verify(accountService, org.mockito.Mockito.times(2)).register(eq(user), snapshots.capture());

        ConnectorAccountService.AccountSnapshot cartao = snapshots.getAllValues().stream()
                .filter(s -> "acc-card".equals(s.providerAccountId())).findFirst().orElseThrow();
        // rótulo comercial vence o nome genérico do produto, e os últimos dígitos
        // entram porque dois cartões do mesmo banco não se distinguem por nome
        assertThat(cartao.name()).isEqualTo("Ultravioleta ····1234");
        assertThat(cartao.type()).isEqualTo(ConnectorAccount.AccountType.CREDIT_CARD);
        assertThat(cartao.institution()).isEqualTo("Nubank");
        assertThat(cartao.pluggyItemId()).isEqualTo(nubank.getId());
        // do metadado do provedor guardamos só o DIA, que é o que se repete
        assertThat(cartao.statementClosingDay()).isEqualTo(10);
        assertThat(cartao.statementDueDay()).isEqualTo(17);
        // o limite TOTAL do cartão viaja no snapshot (o disponível não)
        assertThat(cartao.creditLimit()).isEqualByComparingTo("5330.01");

        ConnectorAccountService.AccountSnapshot conta = snapshots.getAllValues().stream()
                .filter(s -> "acc-bank".equals(s.providerAccountId())).findFirst().orElseThrow();
        assertThat(conta.name()).isEqualTo("Conta Corrente ····3456");
        assertThat(conta.type()).isEqualTo(ConnectorAccount.AccountType.BANK);
        // fechamento/vencimento não existem em conta bancária
        assertThat(conta.statementClosingDay()).isNull();
        assertThat(conta.statementDueDay()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq(StatementFormat.PLUGGY),
                captor.capture(), anyMap());

        // cada linha sai do sync sabendo de onde veio — depois da gravação
        // ninguém mais teria como descobrir
        assertThat(byId(captor.getValue(), "PLUGGY-c1").getAccountId()).isEqualTo(ACCOUNT_IDS.get("acc-card"));
        assertThat(byId(captor.getValue(), "PLUGGY-b1").getAccountId()).isEqualTo(ACCOUNT_IDS.get("acc-bank"));
        assertThat(ACCOUNT_IDS.get("acc-card")).isNotEqualTo(ACCOUNT_IDS.get("acc-bank"));
    }

    @Test
    @DisplayName("conta SEM id no Pluggy é pulada com WARN — não vira a origem literal \"null\"")
    void syncShouldSkipAccountWithoutProviderId() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user)
                        .itemId("item-1").connectorName("Nubank").build()));
        // duas contas malformadas: pelo unique (user_id, provider_account_id) as
        // duas colapsariam na MESMA origem "null" e fundiriam duas faturas
        Map<String, Object> semId = new java.util.HashMap<>();
        semId.put("id", null);
        semId.put("type", "CREDIT");
        semId.put("name", "Cartão sem id");
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                semId,
                Map.of("id", "null", "type", "CREDIT", "name", "Cartão com id textual null"),
                Map.of("id", "acc-card", "type", "CREDIT", "name", "Cartão de verdade")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "c1", "amount", "100.00", "date", "2026-08-11",
                        "description", "IFOOD RESTAURANTE")));
        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 1, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        // só a conta boa foi registrada, e nada foi pedido para as malformadas
        ArgumentCaptor<ConnectorAccountService.AccountSnapshot> snapshots =
                ArgumentCaptor.forClass(ConnectorAccountService.AccountSnapshot.class);
        verify(accountService, org.mockito.Mockito.times(1)).register(eq(user), snapshots.capture());
        assertThat(snapshots.getValue().providerAccountId()).isEqualTo("acc-card");
        verify(pluggyClient, never()).transactions(eq("api-key"), eq("null"), any(), any());
        verify(pluggyClient, never()).transactions(eq("api-key"), org.mockito.ArgumentMatchers.isNull(),
                any(), any());
    }

    @Test
    @DisplayName("origem só é gravada DEPOIS de ler o extrato: falha no meio não deixa conta órfã")
    void syncShouldNotRegisterOriginWhenTransactionsFail() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-card", "type", "CREDIT"),
                Map.of("id", "acc-bank", "type", "BANK")));
        // o cartão vem primeiro (a ordenação garante isso) e é lido normalmente
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "c1", "amount", "100.00", "date", "2026-08-11",
                        "description", "IFOOD RESTAURANTE")));
        // e o Pluggy cai no meio da volta, na conta corrente
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("Pluggy fora do ar"));

        // falha de leitura de conexão viva derruba o lote: 503 dizendo qual banco
        assertThatThrownBy(() -> service.sync(EMAIL, 90))
                .isInstanceOf(br.com.economize.exception.ServiceUnavailableException.class)
                .hasMessageContaining("Não foi possível ler")
                .hasMessageContaining("nada foi importado");

        // a conta corrente NÃO ficou registrada com zero lançamentos: ela
        // apareceria em GET /accounts, abriria vazia e não teria como ser
        // removida. A próxima sync a cria quando conseguir ler o extrato dela
        ArgumentCaptor<ConnectorAccountService.AccountSnapshot> snapshots =
                ArgumentCaptor.forClass(ConnectorAccountService.AccountSnapshot.class);
        verify(accountService, org.mockito.Mockito.times(1)).register(eq(user), snapshots.capture());
        assertThat(snapshots.getValue().providerAccountId()).isEqualTo("acc-card");
        verify(bankStatementService, never()).importFromConnector(any(), anyString(), any(), anyList());
    }

    @Test
    @DisplayName("provedor sem datas de fatura não inventa dia: fechamento e vencimento ficam nulos")
    void syncShouldLeaveStatementDaysNullWithoutProviderMetadata() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-card", "type", "CREDIT", "name", "Cartão")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(null, 0, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        ArgumentCaptor<ConnectorAccountService.AccountSnapshot> snapshot =
                ArgumentCaptor.forClass(ConnectorAccountService.AccountSnapshot.class);
        verify(accountService).register(eq(user), snapshot.capture());
        // é essa ausência que faz a fatura declarar CALENDAR_MONTH em vez de
        // fingir uma precisão que a API não tem
        assertThat(snapshot.getValue().statementClosingDay()).isNull();
        assertThat(snapshot.getValue().statementDueDay()).isNull();
        // sem number, o rótulo é só o nome — nada de "····" pendurado
        assertThat(snapshot.getValue().name()).isEqualTo("Cartão");
    }

    @Test
    @DisplayName("o quadro dos TRÊS lançamentos: origem certa em cada um e nenhuma perna virou receita/despesa")
    void threeLegPictureKeepsOriginAndInternalMark() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK"),
                Map.of("id", "acc-card", "type", "CREDIT")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        Map.of("id", "c1", "amount", "500.00", "date", "2026-08-05",
                                "description", "IFOOD RESTAURANTE"),
                        Map.of("id", "c2", "amount", "-500.00", "date", "2026-08-12",
                                "description", "Pagamento de fatura")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "b1", "amount", "-500.00", "date", "2026-08-12",
                        "description", "PAGAMENTO FATURA CARTAO")));
        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 3, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq(StatementFormat.PLUGGY),
                captor.capture(), anyMap());
        List<ParsedTransaction> parsed = captor.getValue();
        UUID cartao = ACCOUNT_IDS.get("acc-card");
        UUID conta = ACCOUNT_IDS.get("acc-bank");

        // (1) a COMPRA: no cartão, despesa de verdade
        ParsedTransaction compra = byId(parsed, "PLUGGY-c1");
        assertThat(compra.getAccountId()).isEqualTo(cartao);
        assertThat(compra.isInternalTransfer()).isFalse();
        assertThat(compra.getAmount()).isEqualByComparingTo("-500.00");

        // (2) o PAGAMENTO saindo da conta corrente: outra origem, e neutralizado
        ParsedTransaction pagamentoNaConta = byId(parsed, "PLUGGY-b1");
        assertThat(pagamentoNaConta.getAccountId()).isEqualTo(conta);
        assertThat(pagamentoNaConta.isInternalTransfer()).isTrue();

        // (3) o PAGAMENTO dentro do cartão: origem do cartão, também neutralizado
        ParsedTransaction pagamentoNoCartao = byId(parsed, "PLUGGY-c2");
        assertThat(pagamentoNoCartao.getAccountId()).isEqualTo(cartao);
        assertThat(pagamentoNoCartao.isInternalTransfer()).isTrue();

        // a dimensão de conta NÃO reintroduziu a fatura como despesa nem como
        // receita: as duas pernas continuam marcadas, exatamente como na V15
        assertThat(parsed).filteredOn(ParsedTransaction::isInternalTransfer).hasSize(2);
    }

    // ------------------------------------------- uma fonte por conexão (banco)

    /** Duas conexões, uma conta em cada, e devolve o mapa conta -> fonte entregue à importação. */
    private Map<UUID, BankStatementService.UploadSource> syncTwoItems(String nameOfFirst, String nameOfSecond) {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(
                PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1")
                        .connectorName(nameOfFirst).build(),
                PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-2")
                        .connectorName(nameOfSecond).build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK")));
        when(pluggyClient.accounts("api-key", "item-2")).thenReturn(List.of(
                Map.of("id", "acc-card", "type", "CREDIT")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "b1", "amount", "-50.00", "date", "2026-08-10",
                        "description", "PIX MERCADO CENTRAL")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "c1", "amount", "100.00", "date", "2026-08-11",
                        "description", "IFOOD RESTAURANTE")));
        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 2, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, BankStatementService.UploadSource>> sources = ArgumentCaptor.forClass(Map.class);
        // UMA chamada só: a decisão do que entra é tomada sobre a sincronização
        // inteira; quem reparte em um upload por banco é a gravação
        verify(bankStatementService).importFromConnector(eq(user), eq(StatementFormat.PLUGGY),
                anyList(), sources.capture());
        return sources.getValue();
    }

    @Test
    @DisplayName("duas conexões: cada conta leva o nome do banco DELA, e nenhuma se chama Meu Pluggy")
    void eachConnectionCarriesItsOwnBankName() {
        Map<UUID, BankStatementService.UploadSource> sources = syncTwoItems("Inter", "Nubank");

        assertThat(sources).hasSize(2);
        assertThat(sources.get(ACCOUNT_IDS.get("acc-bank")))
                .isEqualTo(new BankStatementService.UploadSource("item-1", "Inter"));
        assertThat(sources.get(ACCOUNT_IDS.get("acc-card")))
                .isEqualTo(new BankStatementService.UploadSource("item-2", "Nubank"));
    }

    @Test
    @DisplayName("conexão sem nome de banco cai no rótulo genérico, sem contaminar a que tem nome")
    void connectionWithoutNameFallsBackToGenericLabel() {
        // em branco e nulo são o mesmo caso: o provedor não disse quem é
        Map<UUID, BankStatementService.UploadSource> sources = syncTwoItems("  ", null);

        assertThat(sources.get(ACCOUNT_IDS.get("acc-bank")))
                .isEqualTo(new BankStatementService.UploadSource("item-1", "Meu Pluggy"));
        assertThat(sources.get(ACCOUNT_IDS.get("acc-card")))
                .isEqualTo(new BankStatementService.UploadSource("item-2", "Meu Pluggy"));
    }

    @Test
    @DisplayName("duas conexões do MESMO banco continuam sendo duas fontes")
    void twoConnectionsOfTheSameBankStayApart() {
        Map<UUID, BankStatementService.UploadSource> sources = syncTwoItems("Inter", "Inter");

        // o nome empata; o que separa é o id da conexão no provedor
        assertThat(sources.get(ACCOUNT_IDS.get("acc-bank")))
                .isNotEqualTo(sources.get(ACCOUNT_IDS.get("acc-card")));
    }

    private ParsedTransaction byId(List<ParsedTransaction> parsed, String externalId) {
        return parsed.stream()
                .filter(tx -> externalId.equals(tx.getExternalId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Transação não importada: " + externalId));
    }

    @Test
    @DisplayName("segunda sync do mesmo usuário enquanto a primeira roda responde 409")
    void secondConcurrentSyncOfSameUserIsRejected() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        // Sem threads nem latch: a segunda sync entra DE DENTRO da primeira,
        // no momento em que ela autentica — determinístico e com a trava tomada
        java.util.concurrent.atomic.AtomicReference<Throwable> segunda = new java.util.concurrent.atomic.AtomicReference<>();
        when(pluggyClient.authenticate()).thenAnswer(inv -> {
            try {
                service.sync(EMAIL, 90);
            } catch (Throwable t) {
                segunda.set(t);
            }
            return "api-key";
        });
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.sync(EMAIL, 90))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nenhuma conexão");

        assertThat(segunda.get())
                .isInstanceOf(br.com.economize.exception.ResourceConflictException.class)
                .hasMessageContaining("sincronização em andamento");
        // a recusada não chegou ao provedor: authenticate só rodou uma vez
        verify(pluggyClient, org.mockito.Mockito.times(1)).authenticate();
    }

    @Test
    @DisplayName("sync que falha no meio solta a trava: a próxima do mesmo usuário roda")
    void failedSyncReleasesTheGuard() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.sync(EMAIL, 90)).isInstanceOf(IllegalArgumentException.class);
        // se a trava tivesse ficado presa, aqui viria 409 em vez do mesmo erro
        assertThatThrownBy(() -> service.sync(EMAIL, 90))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nenhuma conexão");
        verify(pluggyClient, org.mockito.Mockito.times(2)).authenticate();
    }

    // ------------------------------------------------- isolamento por conexão

    private PluggyItem item(String itemId, String banco) {
        return PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId(itemId).connectorName(banco).build();
    }

    private void stubAuth(List<PluggyItem> items) {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(items);
    }

    /** Conexão saudável e recém-coletada: a sync não pede coleta nova. */
    private void stubFresh(String itemId) {
        when(pluggyClient.item("api-key", itemId)).thenReturn(Map.of(
                "status", "UPDATED", "lastUpdatedAt", java.time.OffsetDateTime.now().toString()));
    }

    private void stubImport() {
        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 1, 0, 1, 0, false, "PLUGGY"));
    }

    private void stubBankAccount(String itemId, String accountId) {
        when(pluggyClient.accounts("api-key", itemId)).thenReturn(List.of(Map.of("id", accountId, "type", "BANK")));
        when(pluggyClient.transactions(eq("api-key"), eq(accountId), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "t-" + accountId, "amount", "-10.00", "date", "2026-08-10",
                        "description", "PADARIA")));
    }

    @SuppressWarnings("unchecked")
    private List<ParsedTransaction> importedBatch() {
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService, org.mockito.Mockito.times(1))
                .importFromConnector(eq(user), eq(StatementFormat.PLUGGY), captor.capture(), anyMap());
        return captor.getValue();
    }

    @Test
    @DisplayName("conexão revogada é pulada, não é consultada, e a resposta diz qual banco ficou de fora")
    void revokedItemIsSkippedAndTheOthersImport() {
        PluggyItem revogado = item("item-revogado", "Banco Revogado");
        PluggyItem ok = item("item-ok", "Banco Ok");
        stubAuth(List.of(revogado, ok));
        // 404 no provedor: consentimento revogado lá fora
        when(pluggyClient.item("api-key", "item-revogado")).thenReturn(null);
        stubFresh("item-ok");
        stubBankAccount("item-ok", "acc-ok");
        stubImport();

        PluggySyncService.SyncResult sync = service.sync(EMAIL, 90);

        assertThat(sync.itemsSynced()).isEqualTo(1);
        assertThat(sync.skippedConnections()).containsExactly(
                new br.com.economize.service.connector.OpenFinanceProvider.SkippedConnection(
                        revogado.getId(), "Banco Revogado", "NOT_FOUND"));
        // o item NOT_FOUND não é consultado: nem contas, nem pedido de coleta
        verify(pluggyClient, never()).accounts("api-key", "item-revogado");
        verify(pluggyClient, never()).requestUpdate("api-key", "item-revogado");
        // um evento de importação só, com a linha do banco que funcionou
        assertThat(importedBatch()).extracting(ParsedTransaction::getExternalId)
                .containsExactly("PLUGGY-t-acc-ok");
        assertThat(revogado.getLastSyncedAt()).isNull();
        assertThat(ok.getLastSyncedAt()).isNotNull();
    }

    @Test
    @DisplayName("cartão do banco X que falha na leitura derruba o lote: a fatura paga pelo banco Y não vira despesa")
    void failingCardReadImportsNothing() {
        PluggyItem cartaoX = item("item-x", "Banco X");
        PluggyItem contaY = item("item-y", "Banco Y");
        stubAuth(List.of(cartaoX, contaY));
        stubFresh("item-x");
        stubFresh("item-y");
        when(pluggyClient.accounts("api-key", "item-x")).thenReturn(List.of(Map.of("id", "card-x", "type", "CREDIT")));
        when(pluggyClient.accounts("api-key", "item-y")).thenReturn(List.of(Map.of("id", "acc-y", "type", "BANK")));
        // o cartão (lido primeiro) cai; o pagamento dele nunca chegaria ao pool
        when(pluggyClient.transactions(eq("api-key"), eq("card-x"), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("banco fora"));
        lenient().when(pluggyClient.transactions(eq("api-key"), eq("acc-y"), any(LocalDate.class),
                        any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "y1", "amount", "-500.00", "date", "2026-08-12",
                        "description", "Pagamento de fatura cartao")));

        assertThatThrownBy(() -> service.sync(EMAIL, 90))
                .isInstanceOf(br.com.economize.exception.ServiceUnavailableException.class)
                .hasMessageContaining("Banco X");

        // nada importado: o débito "fatura" do banco Y não ficou gravado como despesa
        verify(bankStatementService, never()).importFromConnector(any(), any(StatementFormat.class), anyList(), anyMap());
        // a saúde, gravada antes da leitura, fica
        verify(pluggyItemRepository).saveAll(List.of(cartaoX, contaY));
        assertThat(cartaoX.getLastSyncedAt()).isNull();
        assertThat(contaY.getLastSyncedAt()).isNull();
    }

    @Test
    @DisplayName("todas exigindo reconexão: 400 dizendo QUAIS bancos, e a saúde gravada")
    void healthIsPersistedEvenWhenNothingIsImported() {
        PluggyItem revogado = item("item-revogado", "Banco Revogado");
        PluggyItem recusado = item("item-recusado", "Banco Recusado");
        stubAuth(List.of(revogado, recusado));
        when(pluggyClient.item("api-key", "item-revogado")).thenReturn(null);
        // retrato velho: a sync pede coleta nova e o provedor recusa por credencial
        when(pluggyClient.item("api-key", "item-recusado")).thenReturn(Map.of("status", "UPDATED"));
        when(pluggyClient.requestUpdate("api-key", "item-recusado")).thenReturn(null);

        assertThatThrownBy(() -> service.sync(EMAIL, 90))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Banco Revogado")
                .hasMessageContaining("Banco Recusado");

        // o "reconecte" e o carimbo do pedido de coleta chegaram ao banco: a
        // próxima tentativa não pede outra coleta nas próximas horas
        verify(pluggyItemRepository).saveAll(List.of(revogado, recusado));
        assertThat(revogado.getStatus()).isEqualTo("NOT_FOUND");
        assertThat(recusado.getStatus()).isEqualTo("NEEDS_USER_ACTION");
        assertThat(recusado.getUpdateRequestedAt()).isNotNull();
        verify(pluggyClient, never()).accounts(anyString(), anyString());
        verify(bankStatementService, never()).importFromConnector(any(), any(StatementFormat.class), anyList(), anyMap());
    }

    @Test
    @DisplayName("UPDATED velho nao apaga reconexao pendente nem pede outra coleta")
    void oldProviderSnapshotKeepsNeedsUserAction() {
        PluggyItem pendente = item("item-pendente", "Banco Pendente");
        pendente.setStatus("NEEDS_USER_ACTION");
        pendente.setUpdateRequestedAt(OffsetDateTime.now().minusHours(7));
        pendente.setProviderUpdatedAt(OffsetDateTime.now().minusDays(2));
        stubAuth(List.of(pendente));
        when(pluggyClient.item("api-key", "item-pendente")).thenReturn(Map.of(
                "status", "UPDATED", "lastUpdatedAt", OffsetDateTime.now().minusDays(2).toString()));

        assertThatThrownBy(() -> service.sync(EMAIL, 90))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Banco Pendente");

        assertThat(pendente.getStatus()).isEqualTo("NEEDS_USER_ACTION");
        verify(pluggyClient, never()).requestUpdate("api-key", "item-pendente");
        verify(pluggyClient, never()).accounts("api-key", "item-pendente");
    }

    @Test
    @DisplayName("coleta posterior ao pedido recusado libera a conexao para leitura")
    void newProviderCollectionClearsNeedsUserAction() {
        PluggyItem reconectado = item("item-reconectado", "Banco Reconectado");
        OffsetDateTime pedido = OffsetDateTime.now().minusMinutes(2);
        reconectado.setStatus("NEEDS_USER_ACTION");
        reconectado.setUpdateRequestedAt(pedido);
        stubAuth(List.of(reconectado));
        when(pluggyClient.item("api-key", "item-reconectado")).thenReturn(Map.of(
                "status", "UPDATED", "lastUpdatedAt", OffsetDateTime.now().minusMinutes(1).toString()));
        stubBankAccount("item-reconectado", "acc-reconectado");
        stubImport();

        PluggySyncService.SyncResult sync = service.sync(EMAIL, 90);

        assertThat(reconectado.getStatus()).isEqualTo("UPDATED");
        assertThat(sync.itemsSynced()).isEqualTo(1);
        verify(pluggyClient, never()).requestUpdate("api-key", "item-reconectado");
    }

    @Test
    @DisplayName("coleta adiada (429 ou já em andamento) não pede reconexão: a conexão segue lida")
    void deferredUpdateKeepsReadingTheLastSnapshot() {
        PluggyItem adiado = item("item-adiado", "Banco Adiado");
        stubAuth(List.of(adiado));
        // retrato velho com o estado do próprio Pluggy
        when(pluggyClient.item("api-key", "item-adiado")).thenReturn(Map.of("status", "UPDATED"));
        when(pluggyClient.requestUpdate("api-key", "item-adiado"))
                .thenThrow(new PluggyClient.UpdateNotNowException(429, null));
        stubBankAccount("item-adiado", "acc-adiado");
        stubImport();

        PluggySyncService.SyncResult sync = service.sync(EMAIL, 90);

        assertThat(adiado.getStatus()).isEqualTo("UPDATED");
        // o pedido conta: a próxima sync não insiste antes da hora
        assertThat(adiado.getUpdateRequestedAt()).isNotNull();
        assertThat(sync.skippedConnections()).isEmpty();
        assertThat(importedBatch()).extracting(ParsedTransaction::getExternalId)
                .containsExactly("PLUGGY-t-acc-adiado");
    }

    @Test
    @DisplayName("modo sem coleta: retrato velho é lido sem pedir atualização ao provedor")
    void readOnlyModeNeverRequestsUpdate() {
        PluggyItem velho = item("item-velho", "Banco Velho");
        stubAuth(List.of(velho));
        // retrato de dois dias: no modo do botão isso dispararia o requestUpdate
        when(pluggyClient.item("api-key", "item-velho")).thenReturn(Map.of(
                "status", "UPDATED", "lastUpdatedAt", OffsetDateTime.now().minusDays(2).toString()));
        stubBankAccount("item-velho", "acc-velho");
        stubImport();

        PluggySyncService.SyncResult sync = service.sync(EMAIL, 90, false);

        verify(pluggyClient, never()).requestUpdate(anyString(), anyString());
        assertThat(velho.getUpdateRequestedAt()).isNull();
        assertThat(sync.itemsSynced()).isEqualTo(1);
        assertThat(importedBatch()).extracting(ParsedTransaction::getExternalId)
                .containsExactly("PLUGGY-t-acc-velho");
    }

    @Test
    @DisplayName("modo sem coleta respeita a mesma trava por usuário do botão")
    void readOnlyModeHonoursUserLock() throws Exception {
        java.util.concurrent.CountDownLatch dentro = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch solta = new java.util.concurrent.CountDownLatch(1);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenAnswer(inv -> {
            dentro.countDown();
            solta.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return false;
        });
        Thread primeira = new Thread(() -> {
            try {
                service.sync(EMAIL, 90);
            } catch (RuntimeException ignorada) {
                // sem credencial: só interessa que ela segurou a trava
            }
        });
        primeira.start();
        assertThat(dentro.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.sync(EMAIL, 90, false))
                .isInstanceOf(br.com.economize.exception.ResourceConflictException.class);

        solta.countDown();
        primeira.join(5000);
    }

    @Test
    @DisplayName("importação que falha não carimba, mas a saúde já foi gravada")
    void importFailureKeepsHealthSaved() {
        PluggyItem ok = item("item-ok", "Banco Ok");
        stubAuth(List.of(ok));
        when(pluggyClient.item("api-key", "item-ok")).thenReturn(Map.of(
                "status", "LOGIN_ERROR", "lastUpdatedAt", java.time.OffsetDateTime.now().toString()));
        stubBankAccount("item-ok", "acc-ok");
        when(bankStatementService.importFromConnector(eq(user), eq(StatementFormat.PLUGGY), anyList(), anyMap()))
                .thenThrow(new IllegalStateException("banco de dados fora"));

        assertThatThrownBy(() -> service.sync(EMAIL, 90)).isInstanceOf(IllegalStateException.class);

        // um save só (o da saúde); o do carimbo nunca chegou
        verify(pluggyItemRepository, org.mockito.Mockito.times(1)).saveAll(List.of(ok));
        assertThat(ok.getStatus()).isEqualTo("LOGIN_ERROR");
        assertThat(ok.getLastSyncedAt()).isNull();
    }
}
