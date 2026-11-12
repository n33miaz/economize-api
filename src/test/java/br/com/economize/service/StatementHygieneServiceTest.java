package br.com.economize.service;

import br.com.economize.model.User;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.family.FamilyTransferService;
import br.com.economize.service.recurrence.RecurrenceDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A faxina que faz as correções de 07/09/2026 sobreviverem ao próximo arquivo.
 *
 * <p>O que este teste protege não é cada varredura — elas têm testes próprios —
 * e sim que as cinco rodem, e <b>nesta ordem</b>.
 */
@ExtendWith(MockitoExtension.class)
class StatementHygieneServiceTest {

    private static final String EMAIL = "dono@economize.test";

    @Mock
    private InternalTransferService internalTransferService;

    @Mock
    private InvestmentFlowService investmentFlowService;

    @Mock
    private WatchmanService watchmanService;

    @Mock
    private FamilyTransferService familyTransferService;

    @Mock
    private DuplicateTransactionService duplicateService;

    @Mock
    private RefundReconciliationService refundService;

    @Mock
    private RecurrenceDetectionService recurrenceDetectionService;

    @Mock
    private UserRepository userRepository;

    private StatementHygieneService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").password("x").build();
        service = new StatementHygieneService(internalTransferService, investmentFlowService,
                familyTransferService, duplicateService, refundService, recurrenceDetectionService,
                watchmanService, userRepository);
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        lenient().when(internalTransferService.reconcileByOwnName(EMAIL))
                .thenReturn(new InternalTransferService.Outcome(1755, 197, true));
        lenient().when(investmentFlowService.sweep(EMAIL, false))
                .thenReturn(new InvestmentFlowService.Outcome(1755, 350,
                        new BigDecimal("16405.15"), new BigDecimal("22810.91"), false, List.of()));
        lenient().when(familyTransferService.reconcile(EMAIL))
                .thenReturn(new FamilyTransferService.Outcome(1755, 68, 1));
        lenient().when(duplicateService.sweep(EMAIL, false))
                .thenReturn(new DuplicateTransactionService.Outcome(
                        1755, 20, new BigDecimal("4855.09"), false, List.of()));
        lenient().when(refundService.sweep(EMAIL, false))
                .thenReturn(new RefundReconciliationService.Outcome(
                        1755, 8, new BigDecimal("574.32"), false, List.of()));
        lenient().when(recurrenceDetectionService.detect(EMAIL))
                .thenReturn(new RecurrenceDetectionService.DetectionSummary(3, 12, 40));
    }

    @Test
    @DisplayName("as cinco varreduras rodam, e a recorrência é a última")
    void shouldRunEveryPassWithDetectionLast() {
        StatementHygieneService.Outcome resultado = service.runFor(EMAIL);

        InOrder ordem = inOrder(internalTransferService, investmentFlowService,
                familyTransferService, duplicateService, recurrenceDetectionService);
        ordem.verify(internalTransferService).reconcileByOwnName(EMAIL);
        // Junto da movimentação própria: é a mesma pergunta e a mesma coluna
        ordem.verify(investmentFlowService).sweep(EMAIL, false);
        ordem.verify(familyTransferService).reconcile(EMAIL);
        ordem.verify(duplicateService).sweep(EMAIL, false);
        // Por último de propósito: rodando antes das marcas, um Pix do dono para
        // ele mesmo vira "despesa mensal" E "receita mensal" na previsão
        ordem.verify(recurrenceDetectionService).detect(EMAIL);

        assertThat(resultado.internalMarked()).isEqualTo(197);
        assertThat(resultado.investmentMarked()).isEqualTo(350);
        assertThat(resultado.familyMarked()).isEqualTo(68);
        assertThat(resultado.duplicatesMarked()).isEqualTo(20);
        assertThat(resultado.seriesCreated()).isEqualTo(3);
        assertThat(resultado.seriesUpdated()).isEqualTo(12);
    }

    @Test
    @DisplayName("a varredura de duplicatas MARCA — não é ensaio")
    void shouldSweepForRealAndNotDryRun() {
        service.runFor(EMAIL);

        // dryRun=true aqui deixaria o relatório bonito e o número errado
        verify(duplicateService).sweep(EMAIL, false);
        verify(duplicateService, never()).sweep(EMAIL, true);
        verify(investmentFlowService).sweep(EMAIL, false);
        verify(investmentFlowService, never()).sweep(EMAIL, true);
    }

    @Test
    @DisplayName("pelo id do usuário, resolve o e-mail antes de varrer")
    void shouldResolveTheUserWhenCalledById() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.runFor(user.getId());

        verify(internalTransferService).reconcileByOwnName(EMAIL);
    }

    @Test
    @DisplayName("usuário que não existe não vira faxina silenciosa")
    void shouldFailLoudlyForAnUnknownUser() {
        UUID desconhecido = UUID.randomUUID();
        when(userRepository.findById(desconhecido)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.runFor(desconhecido))
                .hasMessageContaining("não encontrado");
        verify(internalTransferService, never()).reconcileByOwnName(any());
    }

    @Test
    @DisplayName("depois da importação: quatro varreduras na janela, estorno e recorrência no histórico inteiro")
    void depoisDaImportacaoVarreSoAJanela() {
        HygieneWindow janela = new HygieneWindow(
                java.time.OffsetDateTime.parse("2026-06-10T09:00:00Z"),
                java.time.OffsetDateTime.parse("2026-06-12T18:00:00Z"));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(internalTransferService.reconcileByOwnName(EMAIL, janela))
                .thenReturn(new InternalTransferService.Outcome(3, 2, true));
        when(investmentFlowService.sweep(EMAIL, false, janela))
                .thenReturn(new InvestmentFlowService.Outcome(3, 1,
                        new BigDecimal("500.00"), BigDecimal.ZERO, false, List.of()));
        when(familyTransferService.reconcile(EMAIL, janela))
                .thenReturn(new FamilyTransferService.Outcome(3, 1, 1));
        when(duplicateService.sweep(EMAIL, false, janela))
                .thenReturn(new DuplicateTransactionService.Outcome(
                        5, 2, new BigDecimal("366.47"), false, List.of()));

        StatementHygieneService.Outcome resultado = service.runFor(user.getId(), janela);

        // A mesma ordem da faxina inteira — o motivo de cada posição não muda
        // com o tamanho do recorte
        InOrder ordem = inOrder(internalTransferService, investmentFlowService,
                familyTransferService, duplicateService, refundService, recurrenceDetectionService);
        ordem.verify(internalTransferService).reconcileByOwnName(EMAIL, janela);
        ordem.verify(investmentFlowService).sweep(EMAIL, false, janela);
        ordem.verify(familyTransferService).reconcile(EMAIL, janela);
        ordem.verify(duplicateService).sweep(EMAIL, false, janela);
        // Estorno e recorrência não têm variante em janela: o par do estorno
        // depende do que os outros pares consumiram, e a cadência se mede em
        // meses de histórico
        ordem.verify(refundService).sweep(EMAIL, false);
        ordem.verify(recurrenceDetectionService).detect(EMAIL);

        // Nenhuma das quatro leu o histórico inteiro
        verify(internalTransferService, never()).reconcileByOwnName(EMAIL);
        verify(investmentFlowService, never()).sweep(EMAIL, false);
        verify(familyTransferService, never()).reconcile(EMAIL);
        verify(duplicateService, never()).sweep(EMAIL, false);

        assertThat(resultado.internalMarked()).isEqualTo(2);
        assertThat(resultado.investmentMarked()).isEqualTo(1);
        assertThat(resultado.familyMarked()).isEqualTo(1);
        assertThat(resultado.duplicatesMarked()).isEqualTo(2);
        assertThat(resultado.refundsMarked()).isEqualTo(8);

        // E os seis vigias deixam recado, como na faxina inteira
        for (br.com.economize.model.SweepRun.Kind kind
                : br.com.economize.model.SweepRun.Kind.values()) {
            verify(watchmanService).record(any(), org.mockito.ArgumentMatchers.eq(kind),
                    org.mockito.ArgumentMatchers.anyInt(), any(), any());
        }
        verify(watchmanService).record(user, br.com.economize.model.SweepRun.Kind.DUPLICATE,
                2, new BigDecimal("366.47"), List.of());
    }

    @Test
    @DisplayName("sem janela, a variante da importação é a faxina inteira")
    void semJanelaVarreTudo() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.runFor(user.getId(), null);

        verify(internalTransferService).reconcileByOwnName(EMAIL);
        verify(duplicateService).sweep(EMAIL, false);
    }

    @Test
    @DisplayName("EC-202: cada vigia deixa recado, inclusive o que nao achou nada")
    void cadaVigiaDeixaRecado() {
        service.runFor(EMAIL);

        for (br.com.economize.model.SweepRun.Kind kind
                : br.com.economize.model.SweepRun.Kind.values()) {
            verify(watchmanService).record(any(), org.mockito.ArgumentMatchers.eq(kind),
                    org.mockito.ArgumentMatchers.anyInt(), any(), any());
        }
    }

    @Test
    @DisplayName("Recado que falha nao derruba a faxina — o diario e acessorio")
    void recadoQueFalhaNaoDerruba() {
        org.mockito.Mockito.doThrow(new IllegalStateException("banco fora"))
                .when(watchmanService).record(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                        any(), any());

        StatementHygieneService.Outcome resultado = service.runFor(EMAIL);

        assertThat(resultado.internalMarked()).isEqualTo(197);
    }
}
