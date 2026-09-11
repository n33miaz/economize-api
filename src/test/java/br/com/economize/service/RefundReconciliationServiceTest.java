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
 * A compra estornada para de contar — provada contra os estornos REAIS do
 * dono.
 *
 * <p>Os nove lançamentos com histórico "Estorno" do extrato do Inter de
 * 12/08/2024 a 11/08/2026 estão reproduzidos aqui com valor e data. Oito têm
 * contraparte; o nono é a correção de saldo de 22/04/2026 e não pode ser
 * pareado com nada. Se a heurística mudar, é aqui que se vê o estrago.
 */
@ExtendWith(MockitoExtension.class)
class RefundReconciliationServiceTest {

    private static final String EMAIL = "dono@economize.test";

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private RefundReconciliationService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").password("x").build();
        // lenient: o teste de leitura de descricao nem chega no repositorio
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("Os oito estornos com contraparte são pareados; a correção de saldo não")
    void osOitoParesReaisDoDono() {
        List<BankTransaction> extrato = new ArrayList<>();
        // Os cinco estornos de Aplicação, cada um com a aplicação do mesmo dia
        adicionaPar(extrato, "2026-04-06", "1.20", "Aplicação Cdb Porq Obj Banco Inter Sa");
        adicionaPar(extrato, "2026-04-06", "4.00", "Aplicação Cdb Porq Obj Banco Inter Sa");
        adicionaPar(extrato, "2026-04-02", "318.23", "Aplicação Cdb Porq Obj Banco Inter Sa");
        adicionaPar(extrato, "2026-04-02", "238.19", "Aplicação Cdb Porq Obj Banco Inter Sa");
        adicionaPar(extrato, "2025-12-08", "1.20", "Aplicação Cdb Porq Obj Banco Inter S A");
        // Os três estornos de compra no cartão
        adicionaPar(extrato, "2025-09-01", "4.00",
                "Compra no débito No Estabelecimento Nome Fantasia: Click M Sao Paulo Bra");
        adicionaPar(extrato, "2025-06-30", "4.00",
                "Compra no débito No Estabelecimento Nome Fantasia: Click M Sao Paulo Bra");
        adicionaPar(extrato, "2025-04-08", "3.50",
                "Compra no débito No Estabelecimento Nome Fantasia: Click M Sao Paulo Bra");
        // O nono: R$ 539,70 zerando o saldo negativo do dia. Não há compra de
        // 539,70 — só uma aplicação de 411,35 e um saque de 180,00
        extrato.add(tx("2026-04-22", "539.70", "Estorno Aplicação"));
        extrato.add(tx("2026-04-22", "-411.35", "Aplicação Cdb Porq Obj Banco Inter Sa"));
        extrato.add(tx("2026-04-22", "-180.00", "SAQUE BANCO 24H SAQUE BANCO 24H"));

        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(extrato);

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        assertThat(resultado.pairs())
                .as("oito estornos têm contraparte; o de 539,70 é correção de saldo")
                .isEqualTo(8);
        assertThat(resultado.volume())
                .as("1,20 + 4,00 + 318,23 + 238,19 + 1,20 + 4,00 + 4,00 + 3,50")
                .isEqualByComparingTo(new BigDecimal("574.32"));

        // as duas pernas de cada par entram na marca: 8 pares = 16 linhas
        ArgumentCaptor<Collection<UUID>> marcadas = ArgumentCaptor.forClass(Collection.class);
        verify(bankTransactionRepository).markAsRefundPair(eq(user.getId()), marcadas.capture());
        assertThat(marcadas.getValue()).hasSize(16);
        verify(bankTransactionRepository, org.mockito.Mockito.times(8))
                .linkRefund(eq(user.getId()), any(), any());
    }

    @Test
    @DisplayName("Estorno sem contraparte de mesmo valor não marca nada")
    void semContraparteNaoMarca() {
        // O caso real de 22/04/2026, isolado
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(
                        tx("2026-04-22", "539.70", "Estorno Aplicação"),
                        tx("2026-04-22", "-411.35", "Aplicação Cdb Porq Obj Banco Inter Sa")));

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        assertThat(resultado.pairs()).isZero();
        verify(bankTransactionRepository, never()).markAsRefundPair(any(), any());
    }

    @Test
    @DisplayName("Crédito que não se anuncia como estorno não pareia com despesa igual")
    void creditoComumNaoVirEstorno() {
        // Pix recebido de R$ 50 no mesmo dia de uma compra de R$ 50 é
        // coincidência de valor, não devolução
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(
                        tx("2026-05-10", "50.00", "Pix recebido Fulano De Tal"),
                        tx("2026-05-10", "-50.00", "Compra no débito No Estabelecimento Padaria")));

        assertThat(service.sweep(EMAIL, false).pairs()).isZero();
    }

    @Test
    @DisplayName("O estorno não casa com compra POSTERIOR a ele")
    void naoOlhaParaFrente() {
        // Dinheiro não volta de uma compra que ainda não aconteceu
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(
                        tx("2026-05-10", "30.00", "Estorno Compra cartão"),
                        tx("2026-05-11", "-30.00", "Compra no débito No Estabelecimento X")));

        assertThat(service.sweep(EMAIL, false).pairs()).isZero();
    }

    @Test
    @DisplayName("Fora da janela de três dias não pareia")
    void foraDaJanela() {
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(
                        tx("2026-05-10", "30.00", "Estorno Compra cartão"),
                        tx("2026-05-06", "-30.00", "Compra no débito No Estabelecimento X")));

        assertThat(service.sweep(EMAIL, false).pairs()).isZero();
    }

    @Test
    @DisplayName("dryRun relata e não grava")
    void dryRunNaoGrava() {
        List<BankTransaction> extrato = new ArrayList<>();
        adicionaPar(extrato, "2025-04-08", "3.50", "Compra no débito No Estabelecimento Click M");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(extrato);

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, true);

        assertThat(resultado.pairs()).isEqualTo(1);
        assertThat(resultado.dryRun()).isTrue();
        verify(bankTransactionRepository, never()).markAsRefundPair(any(), any());
        verify(bankTransactionRepository, never()).linkRefund(any(), any(), any());
    }

    @Test
    @DisplayName("Um estorno pareia com UMA compra só, mesmo com três iguais no dia")
    void umParPorEstorno() {
        // Três compras de R$ 4,00 no mesmo dia e um estorno: só uma volta
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(
                        tx("2025-09-01", "4.00", "Estorno Compra cartão"),
                        tx("2025-09-01", "-4.00", "Compra no débito Click M"),
                        tx("2025-09-01", "-4.00", "Compra no débito Padaria"),
                        tx("2025-09-01", "-4.00", "Compra no débito Banca")));

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        assertThat(resultado.pairs()).isEqualTo(1);
        assertThat(resultado.volume()).isEqualByComparingTo(new BigDecimal("4.00"));
    }

    @Test
    @DisplayName("Linha já marcada ou ignorada fica de fora — a varredura é idempotente")
    void idempotente() {
        BankTransaction jaMarcado = tx("2025-09-01", "4.00", "Estorno Compra cartão");
        jaMarcado.setRefunded(true);
        BankTransaction compra = tx("2025-09-01", "-4.00", "Compra no débito Click M");
        compra.setRefunded(true);
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(jaMarcado, compra));

        assertThat(service.sweep(EMAIL, false).pairs()).isZero();
    }

    @Test
    @DisplayName("O que já saiu como movimento próprio não vira par de estorno")
    void oQueJaSaiuNaoPareia() {
        // Os cinco "Estorno Aplicação" do dono já são conta <-> investimento
        // (EC-214). Pareá-los de novo aqui não mudaria número nenhum e inflaria
        // o relatório de estornos com pares que não corrigem nada
        BankTransaction credito = tx("2026-04-06", "1.20", "Estorno Aplicação");
        credito.setInternalTransfer(true);
        BankTransaction aplicacao = tx("2026-04-06", "-1.20", "Aplicação Cdb Porq Obj Banco Inter Sa");
        aplicacao.setInternalTransfer(true);
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(credito, aplicacao));

        assertThat(service.sweep(EMAIL, false).pairs()).isZero();
        verify(bankTransactionRepository, never()).markAsRefundPair(any(), any());
    }

    @Test
    @DisplayName("Acento e caixa não decidem: DEVOLUÇÃO, devolucao e Devolução são a mesma coisa")
    void acentoENaoDecide() {
        for (String texto : List.of("DEVOLUÇÃO de compra", "devolucao de compra",
                "Reembolso parcial", "Cancelamento de compra", "Refund")) {
            BankTransaction credito = tx("2026-05-10", "10.00", texto);
            assertThat(RefundReconciliationService.pareceEstorno(credito))
                    .as("'%s' deveria ser lido como estorno", texto)
                    .isTrue();
        }
        assertThat(RefundReconciliationService.pareceEstorno(
                tx("2026-05-10", "10.00", "Pix recebido Fulano"))).isFalse();
    }

    @Test
    @DisplayName("O Nubank NOMEIA a compra: casa pelo nome, mesmo na fatura anterior")
    void casaPeloNome() {
        // Linha real da fatura de setembro/2026 do dono. O pareamento por
        // valor não acharia: a compra é de 45 dias antes e de outro valor
        BankTransaction credito = tx("2026-08-03", "103.97",
                "Estorno de \"Openai *Chatgpt Subscr\" (ChatGPT)");
        BankTransaction compra = tx("2026-07-03", "-103.97", "Openai *Chatgpt Subscr");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(credito, compra));

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        assertThat(resultado.pairs()).isEqualTo(1);
        assertThat(resultado.volume()).isEqualByComparingTo(new BigDecimal("103.97"));
    }

    @Test
    @DisplayName("Estorno PARCIAL é reportado e NÃO marcado")
    void parcialEReportado() {
        // O caso real: R$ 18,50 de volta sobre uma compra de R$ 604,91.
        // Marcar só o crédito tiraria 18,50 da receita e deixaria a compra
        // inteira na despesa — erra MAIS que deixar os dois contando
        BankTransaction credito = tx("2026-08-08", "18.50",
                "Estorno de \"Mercadolivre*Homenow\" (Mercado Livre)");
        BankTransaction compra = tx("2026-08-05", "-604.91", "Mercadolivre*Homenow");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(credito, compra));

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        assertThat(resultado.pairs()).isZero();
        assertThat(resultado.partials()).singleElement().satisfies(parcial -> {
            assertThat(parcial.refundedAmount()).isEqualByComparingTo(new BigDecimal("18.50"));
            assertThat(parcial.purchaseAmount()).isEqualByComparingTo(new BigDecimal("604.91"));
            assertThat(parcial.purchaseDescription()).isEqualTo("Mercadolivre*Homenow");
        });
        verify(bankTransactionRepository, never()).markAsRefundPair(any(), any());
    }

    @Test
    @DisplayName("Crédito MAIOR que a compra citada não é o estorno dela")
    void creditoMaiorNaoPareia() {
        BankTransaction credito = tx("2026-08-08", "900.00",
                "Estorno de \"Mercadolivre*Homenow\" (Mercado Livre)");
        BankTransaction compra = tx("2026-08-05", "-604.91", "Mercadolivre*Homenow");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(credito, compra));

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        assertThat(resultado.pairs()).isZero();
        assertThat(resultado.partials()).isEmpty();
    }

    @Test
    @DisplayName("Fora da janela de 45 dias, o nome não basta")
    void nomeForaDaJanela() {
        BankTransaction credito = tx("2026-08-08", "50.00", "Estorno de \"Loja Tal\"");
        BankTransaction compra = tx("2026-04-01", "-50.00", "Loja Tal Comercio");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(credito, compra));

        assertThat(service.sweep(EMAIL, false).pairs()).isZero();
    }

    @Test
    @DisplayName("O nome citado só vale quando a linha se anuncia como estorno")
    void aspasSemEstornoNaoContam() {
        assertThat(RefundReconciliationService.nomeDaCompraCitada(
                "Estorno de \"Uber *One Membership U\" (Uber One)"))
                .isEqualTo("Uber *One Membership U");
        // texto com aspas que não é devolução de nada
        assertThat(RefundReconciliationService.nomeDaCompraCitada(
                "Compra em \"Padaria do Ze\"")).isNull();
        // nome curto demais casaria com meio extrato
        assertThat(RefundReconciliationService.nomeDaCompraCitada("Estorno de \"AB\"")).isNull();
        assertThat(RefundReconciliationService.nomeDaCompraCitada(null)).isNull();
    }

    /** A compra e o estorno dela, no mesmo dia — o formato dos oito pares reais. */
    private void adicionaPar(List<BankTransaction> destino, String data, String valor,
                             String descricaoDaCompra) {
        destino.add(tx(data, valor, "Estorno " + descricaoDaCompra.split(" ")[0]));
        destino.add(tx(data, "-" + valor, descricaoDaCompra));
    }

    private BankTransaction tx(String data, String valor, String descricao) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .date(OffsetDateTime.parse(data + "T00:00:00Z"))
                .amount(new BigDecimal(valor))
                .description(descricao)
                .build();
    }
}
