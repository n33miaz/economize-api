package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.SupportTicket;
import br.com.economize.model.User;
import br.com.economize.repository.SupportTicketRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EC-209 — o chamado que não some.
 *
 * <p>O que esta suíte trava é a promessa: o prazo é gravado no chamado, conta
 * dias ÚTEIS, e o histórico sobrevive a tudo.
 */
@ExtendWith(MockitoExtension.class)
class SupportTicketServiceTest {

    private static final String EMAIL = "dono@economize.test";

    @Mock private SupportTicketRepository ticketRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private SupportTicketService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).build();
    }

    private void comUsuario() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    private void gravaOQueChega() {
        when(ticketRepository.save(any(SupportTicket.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Abrir grava o prazo NO chamado, e não numa frase de tela")
    void abrirGravaOPrazo() {
        comUsuario();
        gravaOQueChega();

        SupportTicketService.Ticket t = service.open(EMAIL,
                SupportTicket.Subject.NUMERO_ERRADO, "A soma de setembro não bate",
                "2.2.0", "Análise");

        assertThat(t.respondBy()).isAfter(OffsetDateTime.now());
        assertThat(t.status()).isEqualTo(SupportTicket.Status.OPEN);
        assertThat(t.overdue()).isFalse();
    }

    @Test
    @DisplayName("O prazo conta dias ÚTEIS: sexta à noite não vence no domingo")
    void prazoPulaFimDeSemana() {
        // Sexta-feira, 11/09/2026, 22h
        OffsetDateTime sextaANoite =
                OffsetDateTime.of(2026, 9, 11, 22, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime prazo = SupportTicketService.prazoDe(sextaANoite);

        // Uma promessa de domingo é uma promessa desenhada para ser quebrada
        assertThat(prazo.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        assertThat(prazo.toLocalDate()).isEqualTo("2026-09-15");
    }

    @Test
    @DisplayName("No meio da semana o prazo é dois dias corridos mesmo")
    void prazoNoMeioDaSemana() {
        OffsetDateTime segunda = OffsetDateTime.of(2026, 9, 7, 9, 0, 0, 0, ZoneOffset.UTC);

        assertThat(SupportTicketService.prazoDe(segunda).toLocalDate())
                .isEqualTo("2026-09-09");
    }

    @Test
    @DisplayName("A tela e a versão viajam junto — cada ida e volta custa um dia")
    void contextoViajaJunto() {
        comUsuario();
        gravaOQueChega();

        service.open(EMAIL, SupportTicket.Subject.IMPORTACAO, "O CSV do Inter não entrou",
                "2.2.0", "Extrato");

        ArgumentCaptor<SupportTicket> captor = ArgumentCaptor.forClass(SupportTicket.class);
        verify(ticketRepository).save(captor.capture());
        assertThat(captor.getValue().getAppVersion()).isEqualTo("2.2.0");
        assertThat(captor.getValue().getScreen()).isEqualTo("Extrato");
    }

    @Test
    @DisplayName("Mensagem vazia é recusada com o motivo, e nada é gravado")
    void mensagemVaziaRecusa() {
        comUsuario();

        assertThatThrownBy(() ->
                service.open(EMAIL, SupportTicket.Subject.OUTRO, "   ", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conte o que aconteceu");

        verify(ticketRepository, never()).save(any());
    }

    @Test
    @DisplayName("Mensagem gigante é recusada apontando o caminho")
    void mensagemGiganteRecusa() {
        comUsuario();

        assertThatThrownBy(() -> service.open(EMAIL, SupportTicket.Subject.OUTRO,
                "x".repeat(SupportTicketService.MAX_MENSAGEM + 1), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complemente na resposta");
    }

    @Test
    @DisplayName("Chamados em aberto têm teto, e a recusa lembra que nada se perdeu")
    void tetoDeChamadosAbertos() {
        comUsuario();
        when(ticketRepository.countByUserIdAndStatus(user.getId(), SupportTicket.Status.OPEN))
                .thenReturn((long) SupportTicketService.MAX_ABERTOS);

        assertThatThrownBy(() ->
                service.open(EMAIL, SupportTicket.Subject.OUTRO, "de novo", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                // Quem abre o quarto está ansioso; a frase precisa acalmar, não punir
                .hasMessageContaining("não se perderam");
    }

    @Test
    @DisplayName("Sem assunto, cai em OUTRO em vez de recusar")
    void semAssuntoCaiEmOutro() {
        comUsuario();
        gravaOQueChega();

        assertThat(service.open(EMAIL, null, "não sei classificar", null, null).subject())
                .isEqualTo(SupportTicket.Subject.OUTRO);
    }

    @Test
    @DisplayName("O prazo vencido sem resposta aparece como tal")
    void prazoVencidoApareceComoVencido() {
        comUsuario();
        when(ticketRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()))
                .thenReturn(List.of(SupportTicket.builder()
                        .id(UUID.randomUUID())
                        .status(SupportTicket.Status.OPEN)
                        .respondBy(OffsetDateTime.now().minusDays(1))
                        .message("esperando")
                        .subject(SupportTicket.Subject.OUTRO)
                        .build()));

        // Fingir que está tudo no rumo é o que faz alguém desistir do suporte
        assertThat(service.listFor(EMAIL).get(0).overdue()).isTrue();
    }

    @Test
    @DisplayName("Respondido no prazo não é 'vencido'")
    void respondidoNaoVence() {
        comUsuario();
        when(ticketRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()))
                .thenReturn(List.of(SupportTicket.builder()
                        .id(UUID.randomUUID())
                        .status(SupportTicket.Status.ANSWERED)
                        .respondBy(OffsetDateTime.now().minusDays(5))
                        .answeredAt(OffsetDateTime.now().minusDays(6))
                        .answer("resolvido")
                        .message("era isso")
                        .subject(SupportTicket.Subject.OUTRO)
                        .build()));

        assertThat(service.listFor(EMAIL).get(0).overdue()).isFalse();
    }

    @Test
    @DisplayName("Encerrar é da pessoa, e o chamado FICA no histórico")
    void encerrarNaoApaga() {
        comUsuario();
        SupportTicket ticket = SupportTicket.builder()
                .id(UUID.randomUUID())
                .status(SupportTicket.Status.OPEN)
                .respondBy(OffsetDateTime.now().plusDays(2))
                .message("resolvi sozinho")
                .subject(SupportTicket.Subject.OUTRO)
                .build();
        when(ticketRepository.findByIdAndUserId(ticket.getId(), user.getId()))
                .thenReturn(Optional.of(ticket));

        assertThat(service.close(EMAIL, ticket.getId()).status())
                .isEqualTo(SupportTicket.Status.CLOSED);
        verify(ticketRepository).save(ticket);
        verify(ticketRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Chamado de outra pessoa responde 'não encontrado', e não 403")
    void chamadoDeOutroDono() {
        comUsuario();
        UUID alheio = UUID.randomUUID();
        when(ticketRepository.findByIdAndUserId(alheio, user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.close(EMAIL, alheio))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
