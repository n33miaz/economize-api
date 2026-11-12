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
import static org.mockito.Mockito.times;
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
        // Por padrão a varredura ganha a reivindicação de todo crédito: é o
        // caso de uma passada sozinha. Quem testa a corrida troca a resposta
        lenient().when(bankTransactionRepository.claimRefund(any(), any(), any())).thenReturn(1);
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

        // Os 8 créditos são reivindicados um a um (marca + vínculo numa escrita
        // só); a marca em bloco fica para as 8 compras
        ArgumentCaptor<Collection<UUID>> marcadas = ArgumentCaptor.forClass(Collection.class);
        verify(bankTransactionRepository).markAsRefundPair(eq(user.getId()), marcadas.capture());
        assertThat(marcadas.getValue()).hasSize(8);
        verify(bankTransactionRepository, org.mockito.Mockito.times(8))
                .claimRefund(eq(user.getId()), any(), any());
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
        verify(bankTransactionRepository, never()).claimRefund(any(), any(), any());
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
    @DisplayName("Estorno de cartão que chegou neutro e SEM vínculo ainda procura a compra — e a abate")
    void neutroSemVinculoAindaPareia() {
        // o conector grava o estorno do cartão já fora das somas (crédito em
        // cartão nunca é receita). Isso não pode tirá-lo da conciliação: a
        // compra continuaria inteira na despesa
        BankTransaction compra = tx("2026-09-01", "-604.91", "Mercadolivre*Homenow");
        BankTransaction parcial = tx("2026-09-10", "18.50", "Estorno de \"Mercadolivre*Homenow\"");
        parcial.setRefunded(true);
        BankTransaction inteira = tx("2026-09-03", "-40.00", "IFOOD");
        BankTransaction devolvida = tx("2026-09-04", "40.00", "Estorno IFOOD");
        devolvida.setRefunded(true);
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(parcial, devolvida, inteira, compra));

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        assertThat(resultado.pairs()).isEqualTo(1);
        assertThat(resultado.partials()).hasSize(1);
        verify(bankTransactionRepository).applyPartialRefund(user.getId(), compra.getId(), new BigDecimal("18.50"));
        verify(bankTransactionRepository).claimRefund(user.getId(), parcial.getId(), compra.getId());
        verify(bankTransactionRepository).claimRefund(user.getId(), devolvida.getId(), inteira.getId());
    }

    @Test
    @DisplayName("Estorno neutro que JÁ tem vínculo não pareia de novo")
    void neutroComVinculoNaoPareiaDeNovo() {
        BankTransaction outraCompra = tx("2026-09-04", "-40.00", "IFOOD");
        BankTransaction devolvida = tx("2026-09-04", "40.00", "Estorno IFOOD");
        devolvida.setRefunded(true);
        devolvida.setRefundOfId(UUID.randomUUID());
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(devolvida, outraCompra));

        // o vínculo diz que a compra dela já foi achada: casar com outra de
        // mesmo valor apagaria uma despesa real
        assertThat(service.sweep(EMAIL, false).pairs()).isZero();
        verify(bankTransactionRepository, never()).markAsRefundPair(any(), any());
    }

    @Test
    @DisplayName("Par por valor não atravessa contas: estorno no cartão não casa com débito da conta corrente")
    void parPorValorExigeAMesmaConta() {
        UUID cartao = UUID.randomUUID();
        UUID corrente = UUID.randomUUID();
        BankTransaction estorno = tx("2026-09-04", "50.00", "Estorno compra");
        estorno.setAccountId(cartao);
        // mesmo valor, mesmo dia, OUTRA conta: é coincidência, não a compra
        BankTransaction mercado = tx("2026-09-04", "-50.00", "MERCADO DO BAIRRO");
        mercado.setAccountId(corrente);
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(estorno, mercado));

        assertThat(service.sweep(EMAIL, false).pairs()).isZero();
        verify(bankTransactionRepository, never()).markAsRefundPair(any(), any());
    }

    @Test
    @DisplayName("Na mesma conta o par por valor fecha — e linha sem conta (arquivo antigo) segue pareando")
    void parPorValorNaMesmaContaOuSemConta() {
        UUID cartao = UUID.randomUUID();
        BankTransaction estorno = tx("2026-09-04", "50.00", "Estorno compra");
        estorno.setAccountId(cartao);
        BankTransaction compra = tx("2026-09-03", "-50.00", "LOJA");
        compra.setAccountId(cartao);
        // importada de arquivo antes de existir a dimensão de conta: sem ela
        // não há o que comparar, e barrar aqui desfaria os pares de sempre
        BankTransaction estornoSemConta = tx("2026-09-10", "7.00", "Estorno tarifa");
        BankTransaction tarifa = tx("2026-09-10", "-7.00", "TARIFA");
        tarifa.setAccountId(cartao);
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(estornoSemConta, tarifa, estorno, compra));

        assertThat(service.sweep(EMAIL, false).pairs()).isEqualTo(2);
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
    @DisplayName("Estorno PARCIAL abate a compra: o crédito sai das somas e a compra vale o líquido")
    void parcialAbateACompra() {
        // O caso real: R$ 18,50 de volta sobre uma compra de R$ 604,91.
        // Até 17/09/2026 era só reportado; o dono escolheu "abater a compra".
        // O crédito é marcado e ligado à compra; a compra NÃO é marcada (ela
        // continua contando) e recebe o abate na coluna da V40.
        BankTransaction credito = tx("2026-08-08", "18.50",
                "Estorno de \"Mercadolivre*Homenow\" (Mercado Livre)");
        BankTransaction compra = tx("2026-08-05", "-604.91", "Mercadolivre*Homenow");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(credito, compra));

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        assertThat(resultado.pairs()).isZero();
        assertThat(resultado.volume()).isEqualByComparingTo(new BigDecimal("18.50"));
        assertThat(resultado.partials()).singleElement().satisfies(parcial -> {
            assertThat(parcial.refundedAmount()).isEqualByComparingTo(new BigDecimal("18.50"));
            assertThat(parcial.purchaseAmount()).isEqualByComparingTo(new BigDecimal("604.91"));
            assertThat(parcial.purchaseDescription()).isEqualTo("Mercadolivre*Homenow");
            assertThat(parcial.excessAmount()).isEqualByComparingTo("0.00");
        });
        // A compra NÃO é marcada: ela continua contando, pelo líquido
        verify(bankTransactionRepository, never()).markAsRefundPair(any(), any());
        verify(bankTransactionRepository).claimRefund(user.getId(), credito.getId(), compra.getId());
        verify(bankTransactionRepository).applyPartialRefund(user.getId(), compra.getId(),
                new BigDecimal("18.50"));
    }

    @Test
    @DisplayName("Em dry run o parcial é só reportado, como tudo")
    void parcialEmDryRunNaoGrava() {
        BankTransaction credito = tx("2026-08-08", "18.50",
                "Estorno de \"Mercadolivre*Homenow\" (Mercado Livre)");
        BankTransaction compra = tx("2026-08-05", "-604.91", "Mercadolivre*Homenow");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(credito, compra));

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, true);

        assertThat(resultado.partials()).hasSize(1);
        verify(bankTransactionRepository, never()).markAsRefundPair(any(), any());
        verify(bankTransactionRepository, never()).applyPartialRefund(any(), any(), any());
    }

    @Test
    @DisplayName("O líquido da compra é o bruto menos o que voltou")
    void liquidoDaCompra() {
        BankTransaction compra = tx("2026-08-05", "-604.91", "Mercadolivre*Homenow");
        assertThat(compra.getNetAmount()).isEqualByComparingTo(new BigDecimal("-604.91"));
        compra.setRefundedAmount(new BigDecimal("18.50"));
        assertThat(compra.getNetAmount()).isEqualByComparingTo(new BigDecimal("-586.41"));
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

    // ------------------------------------------------ o teto do estorno
    //
    // A regra: o total estornado de uma compra nunca passa do valor dela.
    // O crédito que traz mais do que resta abate SÓ o que resta. O excedente
    // tem o tratamento mais conservador que o código já conhece — o do estorno
    // anunciado que não acha a compra, que nunca conta como receita: o crédito
    // sai das somas (marcado) e fica ligado à compra, sem abater nada além do
    // teto. E não some: vai dito no relatório (excessAmount) e no log.
    // Ligado, e não solto, porque crédito marcado SEM vínculo é o que a
    // varredura entende como "ainda procurando a compra" — ele seria pareado
    // e relatado de novo a cada passada.

    @Test
    @DisplayName("Dois estornos parciais na mesma passada: juntos abatem só o valor da compra")
    void doisParciaisNaoPassamDaCompra() {
        // A varredura anda do mais novo para o mais velho (a lista vem por
        // data decrescente): o crédito de 10/08 é visto antes e cabe inteiro;
        // é o de 08/08 que encontra só 40 sobrando. Qual dos dois carrega o
        // excedente não muda soma nenhuma — os dois saem das somas e a compra
        // zera — mas fica fixado aqui para não mudar sem alguém ver
        BankTransaction segundo = tx("2026-08-10", "60.00", "Estorno de \"Loja Tal Comercio\"");
        BankTransaction primeiro = tx("2026-08-08", "60.00", "Estorno de \"Loja Tal Comercio\"");
        BankTransaction compra = tx("2026-08-05", "-100.00", "Loja Tal Comercio");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(segundo, primeiro, compra));

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        // 60 + 40, e não 60 + 60: a compra de 100 nunca fica com 120 estornados
        ArgumentCaptor<BigDecimal> abates = ArgumentCaptor.forClass(BigDecimal.class);
        verify(bankTransactionRepository, times(2))
                .applyPartialRefund(eq(user.getId()), eq(compra.getId()), abates.capture());
        assertThat(abates.getAllValues().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(abates.getAllValues().get(1)).isEqualByComparingTo(new BigDecimal("40.00"));
        // O volume é o que deixou de contar como gasto: a compra, não os créditos
        assertThat(resultado.volume()).isEqualByComparingTo(new BigDecimal("100.00"));
        // Os dois créditos saem das somas e ficam ligados à compra: o
        // excedente não vira receita, e a varredura não o procura de novo
        verify(bankTransactionRepository, never()).markAsRefundPair(any(), any());
        verify(bankTransactionRepository).claimRefund(user.getId(), segundo.getId(), compra.getId());
        verify(bankTransactionRepository).claimRefund(user.getId(), primeiro.getId(), compra.getId());
        // O excedente não some: o crédito visto primeiro coube inteiro, o
        // outro trouxe 20 além do que restava — e isso sai dito no relatório
        assertThat(resultado.partials()).hasSize(2);
        assertThat(resultado.partials().get(0).refundId()).isEqualTo(segundo.getId());
        assertThat(resultado.partials().get(1).refundId()).isEqualTo(primeiro.getId());
        assertThat(resultado.partials().get(0).refundedAmount()).isEqualByComparingTo("60.00");
        assertThat(resultado.partials().get(0).excessAmount()).isEqualByComparingTo("0.00");
        assertThat(resultado.partials().get(1).refundedAmount()).isEqualByComparingTo("40.00");
        assertThat(resultado.partials().get(1).excessAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("O segundo parcial chega numa passada posterior: conta o que o banco já abateu")
    void parcialPosteriorRespeitaOQueJaFoiAbatido() {
        // O estado que a primeira passada deixou: compra com 60 abatidos e o
        // primeiro crédito marcado e ligado
        BankTransaction compra = tx("2026-08-05", "-100.00", "Loja Tal Comercio");
        compra.setRefundedAmount(new BigDecimal("60.00"));
        BankTransaction primeiro = tx("2026-08-08", "60.00", "Estorno de \"Loja Tal Comercio\"");
        primeiro.setRefunded(true);
        primeiro.setRefundOfId(compra.getId());
        BankTransaction segundo = tx("2026-09-02", "60.00", "Estorno de \"Loja Tal Comercio\"");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(segundo, primeiro, compra));

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        ArgumentCaptor<BigDecimal> abate = ArgumentCaptor.forClass(BigDecimal.class);
        verify(bankTransactionRepository)
                .applyPartialRefund(eq(user.getId()), eq(compra.getId()), abate.capture());
        assertThat(abate.getValue()).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(resultado.volume()).isEqualByComparingTo(new BigDecimal("40.00"));
        verify(bankTransactionRepository, never()).markAsRefundPair(any(), any());
        verify(bankTransactionRepository).claimRefund(user.getId(), segundo.getId(), compra.getId());
        assertThat(resultado.partials()).singleElement().satisfies(parcial -> {
            assertThat(parcial.refundedAmount()).isEqualByComparingTo("40.00");
            assertThat(parcial.excessAmount()).isEqualByComparingTo("20.00");
        });
    }

    @Test
    @DisplayName("Compra que já voltou inteira não recebe o terceiro crédito")
    void compraEsgotadaNaoRecebeMais() {
        BankTransaction compra = tx("2026-08-05", "-100.00", "Loja Tal Comercio");
        compra.setRefundedAmount(new BigDecimal("100.00"));
        BankTransaction terceiro = tx("2026-09-02", "20.00", "Estorno de \"Loja Tal Comercio\"");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(terceiro, compra));

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        // Cai no caminho de sempre do estorno que não acha a compra: nada é
        // marcado nem abatido por conta dela
        assertThat(resultado.partials()).isEmpty();
        assertThat(resultado.pairs()).isZero();
        verify(bankTransactionRepository, never()).applyPartialRefund(any(), any(), any());
        verify(bankTransactionRepository, never()).markAsRefundPair(any(), any());
    }

    @Test
    @DisplayName("Compra esgotada sai da fila: o crédito vai para a compra anterior da mesma loja")
    void compraEsgotadaCedeAVez() {
        // Quem compra duas vezes na mesma loja e recebe três créditos: os que
        // não cabem mais na compra recente são da anterior, não receita
        BankTransaction recente = tx("2026-08-05", "-100.00", "Loja Tal Comercio");
        recente.setRefundedAmount(new BigDecimal("100.00"));
        BankTransaction anterior = tx("2026-07-20", "-80.00", "Loja Tal Comercio");
        BankTransaction credito = tx("2026-08-20", "20.00", "Estorno de \"Loja Tal Comercio\"");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(credito, recente, anterior));

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        assertThat(resultado.partials()).singleElement().satisfies(parcial -> {
            assertThat(parcial.purchaseId()).isEqualTo(anterior.getId());
            assertThat(parcial.excessAmount()).isEqualByComparingTo("0.00");
        });
        verify(bankTransactionRepository).applyPartialRefund(eq(user.getId()),
                eq(anterior.getId()), any());
        verify(bankTransactionRepository, never()).applyPartialRefund(any(),
                eq(recente.getId()), any());
    }

    @Test
    @DisplayName("Depois do teto, passar de novo não abate nem relata outra vez")
    void tetoEIdempotente() {
        BankTransaction compra = tx("2026-08-05", "-100.00", "Loja Tal Comercio");
        compra.setRefundedAmount(new BigDecimal("100.00"));
        BankTransaction primeiro = tx("2026-08-08", "60.00", "Estorno de \"Loja Tal Comercio\"");
        primeiro.setRefunded(true);
        primeiro.setRefundOfId(compra.getId());
        BankTransaction segundo = tx("2026-09-02", "60.00", "Estorno de \"Loja Tal Comercio\"");
        segundo.setRefunded(true);
        segundo.setRefundOfId(compra.getId());
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(segundo, primeiro, compra));

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        assertThat(resultado.partials()).isEmpty();
        assertThat(resultado.volume()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(bankTransactionRepository, never()).applyPartialRefund(any(), any(), any());
    }

    @Test
    @DisplayName("Compra já abatida em parte não é par exato de um estorno do valor bruto")
    void parPorValorNaoIgnoraOAbate() {
        // -100 com 60 já devolvidos e um "Estorno" de +100 sem nome: parear
        // pelo valor bruto daria 160 estornados numa compra de 100
        BankTransaction compra = tx("2026-08-05", "-100.00", "Loja Tal Comercio");
        compra.setRefundedAmount(new BigDecimal("60.00"));
        BankTransaction credito = tx("2026-08-06", "100.00", "Estorno");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(credito, compra));

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        assertThat(resultado.pairs()).isZero();
        verify(bankTransactionRepository, never()).markAsRefundPair(any(), any());
    }

    // ------------------------------------- duas varreduras ao mesmo tempo
    //
    // A higiene e o botão podem rodar juntos. As duas leem o mesmo crédito
    // solto e chegam à mesma conclusão; o teto do UPDATE segura o total, mas
    // não a dupla contagem — um estorno de 60 abatido duas vezes dá 100, que
    // cabe na compra e é mentira. Quem decide é a reivindicação do crédito:
    // só a varredura que o marcou (1 linha alterada) abate a compra.

    @Test
    @DisplayName("Quem perde a reivindicação do crédito não abate a compra de novo")
    void perdeuAReivindicacaoNaoAbate() {
        BankTransaction credito = tx("2026-08-08", "60.00", "Estorno de \"Loja Tal Comercio\"");
        BankTransaction compra = tx("2026-08-05", "-100.00", "Loja Tal Comercio");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(credito, compra));
        // A outra varredura chegou antes: o UPDATE condicionado altera zero linhas
        when(bankTransactionRepository.claimRefund(user.getId(), credito.getId(), compra.getId()))
                .thenReturn(0);

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        verify(bankTransactionRepository).claimRefund(user.getId(), credito.getId(), compra.getId());
        verify(bankTransactionRepository, never()).applyPartialRefund(any(), any(), any());
        // E não entra no relatório desta passada: é dele que saem os ids que o
        // desfazer solta, e esta passada não marcou nada
        assertThat(resultado.partials()).isEmpty();
        assertThat(resultado.volume()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Quem perde a reivindicação do par inteiro não marca a compra")
    void perdeuAReivindicacaoNaoMarcaACompra() {
        BankTransaction credito = tx("2026-08-06", "100.00", "Estorno");
        BankTransaction compra = tx("2026-08-05", "-100.00", "Loja Tal Comercio");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(credito, compra));
        when(bankTransactionRepository.claimRefund(user.getId(), credito.getId(), compra.getId()))
                .thenReturn(0);

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        verify(bankTransactionRepository, never()).markAsRefundPair(any(), any());
        assertThat(resultado.pairs()).isZero();
        assertThat(resultado.details()).isEmpty();
    }

    @Test
    @DisplayName("De dois créditos, só o reivindicado abate; o outro fica para quem o pegou")
    void soOReivindicadoAbate() {
        BankTransaction segundo = tx("2026-08-10", "30.00", "Estorno de \"Loja Tal Comercio\"");
        BankTransaction primeiro = tx("2026-08-08", "20.00", "Estorno de \"Loja Tal Comercio\"");
        BankTransaction compra = tx("2026-08-05", "-100.00", "Loja Tal Comercio");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(segundo, primeiro, compra));
        when(bankTransactionRepository.claimRefund(user.getId(), primeiro.getId(), compra.getId()))
                .thenReturn(0);

        RefundReconciliationService.Outcome resultado = service.sweep(EMAIL, false);

        verify(bankTransactionRepository, times(1)).applyPartialRefund(any(), any(), any());
        verify(bankTransactionRepository).applyPartialRefund(eq(user.getId()), eq(compra.getId()),
                eq(new BigDecimal("30.00")));
        assertThat(resultado.partials()).singleElement()
                .satisfies(p -> assertThat(p.refundId()).isEqualTo(segundo.getId()));
        assertThat(resultado.volume()).isEqualByComparingTo("30.00");
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
