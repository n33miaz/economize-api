package br.com.economize.service;

import br.com.economize.model.SweepRun;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.SweepRunRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EC-202 — os vigias prestam contas, e o desfazer solta o que ELES marcaram.
 *
 * <p>Seis varreduras mudam os números do usuário depois de cada importação e
 * não deixavam rastro nenhum. Reversível na teoria e invisível na prática é o
 * mesmo que irreversível.
 */
@ExtendWith(MockitoExtension.class)
class WatchmanServiceTest {

    private static final String EMAIL = "dono@economize.test";

    @Mock
    private SweepRunRepository sweepRunRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private WatchmanService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").password("x").build();
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        lenient().when(sweepRunRepository.save(any(SweepRun.class)))
                .thenAnswer(invocacao -> {
                    SweepRun run = invocacao.getArgument(0);
                    if (run.getId() == null) run.setId(UUID.randomUUID());
                    return run;
                });
    }

    // ------------------------------------------------------------- registro

    @Test
    @DisplayName("A passada grava o resumo E os ids que ela tocou")
    void gravaResumoEIds() {
        List<UUID> tocadas = List.of(UUID.randomUUID(), UUID.randomUUID());

        SweepRun run = service.record(user, SweepRun.Kind.INVESTMENT_FLOW, 2,
                new BigDecimal("39216.06"), tocadas);

        verify(sweepRunRepository).addItems(eq(run.getId()), eq(tocadas));
        assertThat(run.getAffected()).isEqualTo(2);
    }

    @Test
    @DisplayName("Passada sem nada achado é gravada mesmo assim")
    void passadaVaziaEGravada() {
        // "Passei e não achei nada" é resposta; sem ela o silêncio é ambíguo
        service.record(user, SweepRun.Kind.REFUND, 0, BigDecimal.ZERO, List.of());

        verify(sweepRunRepository).save(any(SweepRun.class));
        verify(sweepRunRepository, never()).addItems(any(), anyList());
    }

    // -------------------------------------------------------------- recados

    @Test
    @DisplayName("O recado diz quantos e quanto muda nas somas")
    void recadoComNumeroEVolume() {
        when(sweepRunRepository.findTop50ByUserIdOrderByRanAtDesc(user.getId()))
                .thenReturn(List.of(passada(SweepRun.Kind.INVESTMENT_FLOW, 350,
                        new BigDecimal("39216.06"))));

        WatchmanService.Note recado = service.notesFor(EMAIL).get(0);

        assertThat(recado.watchman()).isEqualTo("Vigia da poupança");
        assertThat(recado.role()).isEqualTo("Aplicar não é gastar; resgatar não é ganhar.");
        // O recado vai INTEIRO para a tela, então o dinheiro sai escrito como
        // o Brasil escreve. "R$ 39216.06" no meio de um app em português é a
        // linha que faz o usuário duvidar do resto
        assertThat(recado.message())
                .contains("350 lançamentos")
                .contains("39.216,06")
                .doesNotContain("39216.06");
    }

    @Test
    @DisplayName("Passada sem achado tem frase própria, não vazio")
    void recadoDeNadaAchado() {
        when(sweepRunRepository.findTop50ByUserIdOrderByRanAtDesc(user.getId()))
                .thenReturn(List.of(passada(SweepRun.Kind.DUPLICATE, 0, BigDecimal.ZERO)));

        assertThat(service.notesFor(EMAIL).get(0).message())
                .isEqualTo("Passei e não achei nada novo.");
    }

    @Test
    @DisplayName("Singular e plural, porque '1 pares' é o detalhe que estraga a frase")
    void singularEPlural() {
        when(sweepRunRepository.findTop50ByUserIdOrderByRanAtDesc(user.getId()))
                .thenReturn(List.of(
                        passada(SweepRun.Kind.DUPLICATE, 1, null),
                        passada(SweepRun.Kind.DUPLICATE, 18, null)));

        List<WatchmanService.Note> recados = service.notesFor(EMAIL);

        assertThat(recados.get(0).message()).contains("1 par duplicado");
        assertThat(recados.get(1).message()).contains("18 pares duplicados");
    }

    @Test
    @DisplayName("A recorrência não oferece desfazer — ela não marca linha nenhuma")
    void recorrenciaNaoOfereceDesfazer() {
        // Botão que não faz o que promete é pior do que botão nenhum
        when(sweepRunRepository.findTop50ByUserIdOrderByRanAtDesc(user.getId()))
                .thenReturn(List.of(passada(SweepRun.Kind.RECURRENCE, 3, null)));

        assertThat(service.notesFor(EMAIL).get(0).canUndo()).isFalse();
    }

    // ------------------------------------------------------------- desfazer

    @Test
    @DisplayName("Desfazer solta EXATAMENTE as linhas que aquela passada marcou")
    void desfazSoltaAsLinhasDaPassada() {
        SweepRun run = passada(SweepRun.Kind.INVESTMENT_FLOW, 2, null);
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(sweepRunRepository.findByIdAndUserId(run.getId(), user.getId()))
                .thenReturn(Optional.of(run));
        when(sweepRunRepository.findItemIds(run.getId())).thenReturn(ids);

        WatchmanService.Note recado = service.undo(EMAIL, run.getId());

        verify(bankTransactionRepository).clearInternalTransfer(user.getId(), ids);
        assertThat(recado.undone()).isTrue();
        assertThat(run.getUndoneAt()).isNotNull();
    }

    @Test
    @DisplayName("Cada vigia solta a SUA marca, não a do vizinho")
    void cadaVigiaSoltaASuaMarca() {
        List<UUID> ids = List.of(UUID.randomUUID());

        desfaz(SweepRun.Kind.DUPLICATE, ids);
        verify(bankTransactionRepository).clearIgnored(user.getId(), ids);

        desfaz(SweepRun.Kind.REFUND, ids);
        verify(bankTransactionRepository).clearRefund(user.getId(), ids);

        desfaz(SweepRun.Kind.FAMILY_TRANSFER, ids);
        verify(bankTransactionRepository).clearFamilyTransfer(user.getId(), ids);
    }

    @Test
    @DisplayName("Desfazer duas vezes é recusado, e a segunda não mexe em nada")
    void desfazerDuasVezes() {
        SweepRun run = passada(SweepRun.Kind.DUPLICATE, 5, null);
        run.setUndoneAt(OffsetDateTime.now().minusHours(1));
        when(sweepRunRepository.findByIdAndUserId(run.getId(), user.getId()))
                .thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.undo(EMAIL, run.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("já foi desfeita");
        verify(bankTransactionRepository, never()).clearIgnored(any(), anyList());
    }

    @Test
    @DisplayName("Desfazer a recorrência é recusado com o caminho certo na frase")
    void desfazerRecorrenciaExplicaOnde() {
        SweepRun run = passada(SweepRun.Kind.RECURRENCE, 3, null);
        when(sweepRunRepository.findByIdAndUserId(run.getId(), user.getId()))
                .thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.undo(EMAIL, run.getId()))
                .hasMessageContaining("Recorrências");
    }

    @Test
    @DisplayName("Passada de outro dono responde 'não encontrada'")
    void passadaAlheia() {
        UUID alheia = UUID.randomUUID();
        when(sweepRunRepository.findByIdAndUserId(alheia, user.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.undo(EMAIL, alheia))
                .hasMessageContaining("não encontrada");
    }

    @Test
    @DisplayName("Desfazer passada sem itens não estoura")
    void desfazerSemItens() {
        SweepRun run = passada(SweepRun.Kind.DUPLICATE, 0, null);
        when(sweepRunRepository.findByIdAndUserId(run.getId(), user.getId()))
                .thenReturn(Optional.of(run));
        when(sweepRunRepository.findItemIds(run.getId())).thenReturn(List.of());

        assertThat(service.undo(EMAIL, run.getId()).undone()).isTrue();
        verify(bankTransactionRepository, never()).clearIgnored(any(), anyList());
    }

    @Test
    @DisplayName("Todo vigia tem nome e uma linha de função — nenhum fica mudo")
    void todoVigiaTemNomeEFuncao() {
        for (SweepRun.Kind kind : SweepRun.Kind.values()) {
            assertThat(WatchmanService.nameOf(kind)).isNotBlank();
            assertThat(WatchmanService.roleOf(kind))
                    .as("%s", kind)
                    .isNotBlank()
                    .endsWith(".");
        }
    }

    // ------------------------------------------------------------------ apoio

    private void desfaz(SweepRun.Kind kind, List<UUID> ids) {
        SweepRun run = passada(kind, ids.size(), null);
        when(sweepRunRepository.findByIdAndUserId(run.getId(), user.getId()))
                .thenReturn(Optional.of(run));
        when(sweepRunRepository.findItemIds(run.getId())).thenReturn(ids);
        service.undo(EMAIL, run.getId());
    }

    private SweepRun passada(SweepRun.Kind kind, int afetados, BigDecimal volume) {
        return SweepRun.builder()
                .id(UUID.randomUUID())
                .user(user)
                .kind(kind)
                .affected(afetados)
                .volume(volume)
                .ranAt(OffsetDateTime.now().minusMinutes(5))
                .build();
    }
}
