package br.com.economize.service.connector.pluggy;

import br.com.economize.model.CardBill;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.PluggyItem;
import br.com.economize.model.User;
import br.com.economize.repository.CardBillRepository;
import br.com.economize.repository.ConnectorAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A fatura que o BANCO fechou.
 *
 * <p>O app deduzia a fatura somando os lançamentos que tinha, e na conta do
 * dono isso dava R$ 775,67 num cartão cuja fatura fechada, no banco, foi de
 * R$ 2.311,49 — a diferença eram compras que o conector não trouxe. Guardar o
 * número do emissor ao lado do nosso é o que transforma essa diferença num
 * aviso, em vez de um erro silencioso.
 */
@ExtendWith(MockitoExtension.class)
class CardBillServiceTest {

    @Mock
    private PluggyClient pluggyClient;

    @Mock
    private CardBillRepository cardBillRepository;

    @Mock
    private ConnectorAccountRepository accountRepository;

    private CardBillService service;

    private User dono;
    private ConnectorAccount cartao;
    private PluggyItem item;

    private static final String NO_PROVEDOR = "acc-gold-8210";

    @BeforeEach
    void preparar() {
        service = new CardBillService(pluggyClient, cardBillRepository, accountRepository);
        dono = User.builder().id(UUID.randomUUID()).email("dono@economize.test")
                .name("Dono").password("x").build();
        cartao = ConnectorAccount.builder().id(UUID.randomUUID()).user(dono)
                .name("GOLD ····8210").providerAccountId(NO_PROVEDOR)
                .type(ConnectorAccount.AccountType.CREDIT_CARD).build();
        item = PluggyItem.builder().id(UUID.randomUUID()).user(dono)
                .itemId("item-1").connectorName("MeuPluggy").build();
        lenient().when(cardBillRepository.save(any(CardBill.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /** A resposta real do Pluggy, medida na conta do dono em 21/09/2026. */
    private Map<String, Object> faturaDoPluggy() {
        return Map.of(
                "id", "bill-set-2026",
                "billClosingDate", "2026-08-31T00:00:00.000Z",
                "dueDate", "2026-09-07T00:00:00.000Z",
                "totalAmount", 2311.49,
                "minimumPaymentAmount", 347.63,
                "totalAmountCurrencyCode", "BRL",
                "allowsInstallments", true,
                "financeCharges", List.of(
                        Map.of("type", "JUROS", "amount", 12.50),
                        Map.of("type", "MULTA", "amount", 7.50)));
    }

    private void contaExiste() {
        when(pluggyClient.accounts(anyString(), eq("item-1")))
                .thenReturn(List.of(Map.of("id", NO_PROVEDOR, "type", "CREDIT")));
        when(accountRepository.findByUserIdAndProviderAccountId(dono.getId(), NO_PROVEDOR))
                .thenReturn(Optional.of(cartao));
    }

    @Test
    @DisplayName("a fatura do emissor vira linha, com o mínimo que o app não tinha")
    void gravaAFaturaDoEmissor() {
        contaExiste();
        when(pluggyClient.bills(anyString(), eq(NO_PROVEDOR))).thenReturn(List.of(faturaDoPluggy()));
        when(cardBillRepository.findByAccountIdAndExternalId(cartao.getId(), "bill-set-2026"))
                .thenReturn(Optional.empty());

        assertThat(service.sync(dono, "chave", List.of(item))).isEqualTo(1);

        ArgumentCaptor<CardBill> capturada = ArgumentCaptor.forClass(CardBill.class);
        verify(cardBillRepository).save(capturada.capture());
        CardBill b = capturada.getValue();
        assertThat(b.getAccountId()).isEqualTo(cartao.getId());
        // A data vem ISO com hora; o dia basta e é como o usuário fala dela
        assertThat(b.getClosingDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(b.getDueDate()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(b.getTotalAmount()).isEqualByComparingTo("2311.49");
        // pagamento mínimo não existe em nenhum outro lugar do app: só o
        // emissor tem esse número
        assertThat(b.getMinimumPayment()).isEqualByComparingTo("347.63");
        // os encargos vêm como lista de itens; a tela quer um número
        assertThat(b.getFinanceCharges()).isEqualByComparingTo("20.00");
        assertThat(b.isAllowsInstallments()).isTrue();
        assertThat(b.getCurrency()).isEqualTo("BRL");
    }

    @Test
    @DisplayName("reler a mesma fatura ATUALIZA — fatura em aberto muda de total até fechar")
    void releituraAtualizaEmVezDeDuplicar() {
        contaExiste();
        when(pluggyClient.bills(anyString(), eq(NO_PROVEDOR))).thenReturn(List.of(faturaDoPluggy()));
        CardBill jaGravada = CardBill.builder()
                .id(UUID.randomUUID()).user(dono).accountId(cartao.getId())
                .externalId("bill-set-2026").totalAmount(new BigDecimal("900.00")).build();
        when(cardBillRepository.findByAccountIdAndExternalId(cartao.getId(), "bill-set-2026"))
                .thenReturn(Optional.of(jaGravada));

        service.sync(dono, "chave", List.of(item));

        ArgumentCaptor<CardBill> capturada = ArgumentCaptor.forClass(CardBill.class);
        verify(cardBillRepository).save(capturada.capture());
        // a MESMA linha, com o número novo: não há nada do nosso lado a preservar
        assertThat(capturada.getValue().getId()).isEqualTo(jaGravada.getId());
        assertThat(capturada.getValue().getTotalAmount()).isEqualByComparingTo("2311.49");
    }

    @Test
    @DisplayName("número e data ilegíveis viram nulo em vez de derrubar a leitura")
    void valorIlegivelViraNulo() {
        contaExiste();
        when(pluggyClient.bills(anyString(), eq(NO_PROVEDOR))).thenReturn(List.of(Map.of(
                "id", "bill-torta",
                "totalAmount", "isto não é número",
                "dueDate", "31/09/2026",
                "billClosingDate", "2026-08-31",
                "financeCharges", List.of(Map.of("type", "JUROS")))));
        when(cardBillRepository.findByAccountIdAndExternalId(cartao.getId(), "bill-torta"))
                .thenReturn(Optional.empty());

        service.sync(dono, "chave", List.of(item));

        ArgumentCaptor<CardBill> capturada = ArgumentCaptor.forClass(CardBill.class);
        verify(cardBillRepository).save(capturada.capture());
        CardBill b = capturada.getValue();
        // uma fatura com um campo torto ainda vale pelos outros: derrubar a
        // leitura inteira por causa de um número perderia as outras 39
        assertThat(b.getTotalAmount()).isNull();
        assertThat(b.getDueDate()).isNull();
        assertThat(b.getClosingDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        // encargo sem valor soma zero, não explode
        assertThat(b.getFinanceCharges()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("o literal \"null\" que o provedor às vezes manda não vira id nem moeda")
    void literalNullNaoViraTexto() {
        contaExiste();
        when(pluggyClient.bills(anyString(), eq(NO_PROVEDOR))).thenReturn(List.of(
                Map.of("id", "null", "totalAmount", 10.0)));

        assertThat(service.sync(dono, "chave", List.of(item))).isZero();
        verify(cardBillRepository, never()).save(any());
    }

    @Test
    @DisplayName("lista de faturas vazia não grava nada e não reclama")
    void semFaturasNaoGravaNada() {
        contaExiste();
        when(pluggyClient.bills(anyString(), eq(NO_PROVEDOR))).thenReturn(List.of());

        assertThat(service.sync(dono, "chave", List.of(item))).isZero();
        verify(cardBillRepository, never()).save(any());
    }

    @Test
    @DisplayName("conta corrente não tem fatura e nem é perguntada")
    void contaCorrenteNaoEPerguntada() {
        when(pluggyClient.accounts(anyString(), eq("item-1")))
                .thenReturn(List.of(Map.of("id", "acc-corrente", "type", "BANK")));

        assertThat(service.sync(dono, "chave", List.of(item))).isZero();

        verify(pluggyClient, never()).bills(anyString(), anyString());
    }

    @Test
    @DisplayName("cartão que ainda não existe do nosso lado é pulado — fatura sem cartão não significa nada")
    void cartaoDesconhecidoEPulado() {
        when(pluggyClient.accounts(anyString(), eq("item-1")))
                .thenReturn(List.of(Map.of("id", NO_PROVEDOR, "type", "CREDIT")));
        when(accountRepository.findByUserIdAndProviderAccountId(dono.getId(), NO_PROVEDOR))
                .thenReturn(Optional.empty());

        assertThat(service.sync(dono, "chave", List.of(item))).isZero();

        verify(pluggyClient, never()).bills(anyString(), anyString());
    }

    @Test
    @DisplayName("emissor fora do ar NÃO derruba a sincronização — extrato é o produto, fatura é enfeite")
    void falhaDoEmissorNaoDerrubaOSync() {
        contaExiste();
        when(pluggyClient.bills(anyString(), eq(NO_PROVEDOR)))
                .thenThrow(new RuntimeException("502 do emissor"));

        assertThatCode(() -> service.sync(dono, "chave", List.of(item))).doesNotThrowAnyException();
        verify(cardBillRepository, never()).save(any());
    }

    @Test
    @DisplayName("fatura sem id do provedor é ignorada: sem ela não há como atualizar depois")
    void faturaSemIdEIgnorada() {
        contaExiste();
        when(pluggyClient.bills(anyString(), eq(NO_PROVEDOR)))
                .thenReturn(List.of(Map.of("totalAmount", 100.0)));

        assertThat(service.sync(dono, "chave", List.of(item))).isZero();
        verify(cardBillRepository, never()).save(any());
    }

    @Test
    @DisplayName("campo ausente vira nulo, não zero — 'não sei' e 'zero' são coisas diferentes")
    void campoAusenteViraNulo() {
        contaExiste();
        when(pluggyClient.bills(anyString(), eq(NO_PROVEDOR)))
                .thenReturn(List.of(Map.of("id", "bill-magra", "totalAmount", 10.0)));
        when(cardBillRepository.findByAccountIdAndExternalId(cartao.getId(), "bill-magra"))
                .thenReturn(Optional.empty());

        service.sync(dono, "chave", List.of(item));

        ArgumentCaptor<CardBill> capturada = ArgumentCaptor.forClass(CardBill.class);
        verify(cardBillRepository).save(capturada.capture());
        CardBill b = capturada.getValue();
        // zero em "pagamento mínimo" seria uma mentira útil: a tela mostraria
        // "mínimo R$ 0,00" onde o certo é não mostrar linha nenhuma
        assertThat(b.getMinimumPayment()).isNull();
        assertThat(b.getFinanceCharges()).isNull();
        assertThat(b.getDueDate()).isNull();
        assertThat(b.isAllowsInstallments()).isFalse();
    }
}
