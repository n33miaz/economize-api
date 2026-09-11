package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Aplicar não é gastar — provado contra o vocabulário REAL do extrato do dono.
 *
 * <p>Todas as descrições daqui foram copiadas do extrato do Inter de 12/08/2024
 * a 11/08/2026, no formato em que o {@code CsvParser} as grava (Histórico +
 * Descrição colados). Se alguém mexer na lista de marcadores, é aqui que as
 * três armadilhas do extrato real reclamam.
 */
@ExtendWith(MockitoExtension.class)
class InvestmentFlowServiceTest {

    private static final String EMAIL = "dono@economize.test";

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private InvestmentFlowService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").password("x").build();
        // lenient: os testes de leitura de descrição nem chegam no repositório
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("As seis famílias de linha do extrato real saem das somas")
    void asSeisFamiliasReais() {
        List<BankTransaction> extrato = new ArrayList<>(List.of(
                tx("-411.35", "Aplicação Cdb Porq Obj Banco Inter Sa"),
                tx("51.65", "Resgate Cdb Porquinho Banco Inter Sa"),
                tx("278.02", "Crédito Tesouro Direto * Prov * Venda Td Lft"),
                tx("-179.15", "Débito Tesouro Direto Compra Td 91975524"),
                tx("-323.50", "Debito Online Td Prot.88885383 Selic 2031"),
                tx("-136.75", "Debito Conta Global De Inv 08834"),
                tx("2714.77", "Credito Conta Global De Inv 08832"),
                tx("539.70", "Estorno Aplicação")));

        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(extrato);

        InvestmentFlowService.Outcome resultado = service.sweep(EMAIL, false);

        assertThat(resultado.marked()).isEqualTo(8);
        assertThat(resultado.applied())
                .as("411,35 + 179,15 + 323,50 + 136,75")
                .isEqualByComparingTo(new BigDecimal("1050.75"));
        assertThat(resultado.redeemed())
                .as("51,65 + 278,02 + 2.714,77 + 539,70")
                .isEqualByComparingTo(new BigDecimal("3584.14"));

        ArgumentCaptor<Collection<UUID>> marcadas = ArgumentCaptor.forClass(Collection.class);
        verify(bankTransactionRepository).markAsInternalTransfer(eq(user.getId()), marcadas.capture());
        assertThat(marcadas.getValue()).hasSize(8);
    }

    @Test
    @DisplayName("As três armadilhas do extrato real continuam contando: imposto, IOF e pontos")
    void asTresArmadilhas() {
        // Cada uma fala de investimento e cada uma é dinheiro de verdade
        assertThat(InvestmentFlowService.ehMovimentoDeInvestimento("Imposto IR/IOF -  Tesouro direto"))
                .as("20 linhas, R$ 73,80 de imposto pago")
                .isFalse();
        assertThat(InvestmentFlowService.ehMovimentoDeInvestimento("Debito Iof Conta Global De Inv 08836"))
                .as("6 linhas, R$ 17,78 de IOF")
                .isFalse();
        assertThat(InvestmentFlowService.ehMovimentoDeInvestimento("Cred Pontos Meu Porquinho Resgate Pontos"))
                .as("9 linhas, R$ 31,75 — resgatar pontos é receita")
                .isFalse();
        assertThat(InvestmentFlowService.ehMovimentoDeInvestimento("Cred Pontos Cashback Extra Resgate Pontos"))
                .isFalse();
    }

    @Test
    @DisplayName("Joselice não é Selic: o casamento é por palavra inteira")
    void joseliceNaoESelic() {
        // Linha real do extrato do dono. Um 'contém' marcaria uma compra na rua
        // como movimento de Tesouro Selic
        assertThat(InvestmentFlowService.ehMovimentoDeInvestimento(
                "Compra no débito Joselice Do Nascimento Barueri       Bra"))
                .isFalse();
        // e outras palavras que carregam um marcador no meio
        assertThat(InvestmentFlowService.ehMovimentoDeInvestimento("Pix enviado Ltda Comercio")).isFalse();
        assertThat(InvestmentFlowService.ehMovimentoDeInvestimento("Compra no débito Poupancarnes")).isFalse();
        assertThat(InvestmentFlowService.ehMovimentoDeInvestimento("Pix enviado Lcapital Servicos")).isFalse();
    }

    @Test
    @DisplayName("Gasto e renda de verdade não são tocados")
    void oQueEDinheiroDeVerdadeFica() {
        for (String descricao : List.of(
                "Pix enviado Claudia Cristina P Santana",
                "Compra no débito No Estabelecimento Padaria",
                "Salário recebido - Portabilidade",
                "Pagamento efetuado Fatura Cartao",
                "SAQUE BANCO 24H SAQUE BANCO 24H",
                "Cashback Compra Inter Shop")) {
            assertThat(InvestmentFlowService.ehMovimentoDeInvestimento(descricao))
                    .as("'%s' é dinheiro de verdade", descricao)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Acento e caixa não decidem")
    void acentoENaoDecide() {
        for (String descricao : List.of("APLICAÇÃO CDB", "aplicacao cdb", "Aplicação Cdb")) {
            assertThat(InvestmentFlowService.ehMovimentoDeInvestimento(descricao))
                    .as("'%s'", descricao)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("dryRun relata e não grava")
    void dryRunNaoGrava() {
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(tx("-411.35", "Aplicação Cdb Porq Obj Banco Inter Sa")));

        InvestmentFlowService.Outcome resultado = service.sweep(EMAIL, true);

        assertThat(resultado.marked()).isEqualTo(1);
        assertThat(resultado.dryRun()).isTrue();
        assertThat(resultado.details()).singleElement()
                .extracting(InvestmentFlowService.Move::description)
                .isEqualTo("Aplicação Cdb Porq Obj Banco Inter Sa");
        verify(bankTransactionRepository, never()).markAsInternalTransfer(any(), any());
    }

    @Test
    @DisplayName("Linha já marcada ou ignorada fica de fora — a varredura é idempotente")
    void idempotente() {
        BankTransaction jaMarcada = tx("-411.35", "Aplicação Cdb Porq Obj Banco Inter Sa");
        jaMarcada.setInternalTransfer(true);
        BankTransaction ignorada = tx("51.65", "Resgate Cdb Porquinho Banco Inter Sa");
        ignorada.setIgnored(true);
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(jaMarcada, ignorada));

        InvestmentFlowService.Outcome resultado = service.sweep(EMAIL, false);

        assertThat(resultado.marked()).isZero();
        verify(bankTransactionRepository, never()).markAsInternalTransfer(any(), any());
    }

    @Test
    @DisplayName("Descrição vazia não vira movimento de investimento")
    void descricaoVazia() {
        assertThat(InvestmentFlowService.ehMovimentoDeInvestimento(null)).isFalse();
        assertThat(InvestmentFlowService.ehMovimentoDeInvestimento("")).isFalse();
        assertThat(InvestmentFlowService.ehMovimentoDeInvestimento("   ")).isFalse();
    }

    private BankTransaction tx(String valor, String descricao) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .date(OffsetDateTime.parse("2026-04-22T00:00:00Z"))
                .amount(new BigDecimal(valor))
                .description(descricao)
                .build();
    }
}
