package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.SupportTicket;
import br.com.economize.model.User;
import br.com.economize.repository.SupportTicketRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Falar com uma pessoa, com prazo e histórico — EC-209.
 *
 * <p><b>O defeito que isto fecha.</b> Suporte dentro de um chat efêmero some
 * quando o app fecha. A pessoa descreve o problema, fecha o app, volta no dia
 * seguinte e não encontra nem o que escreveu nem se alguém leu — e desiste. Do
 * ponto de vista de quem mede chamados, desistência é indistinguível de
 * problema resolvido, e é por isso que esse desenho sobrevive em tanto app.
 *
 * <p><b>O prazo é prometido na abertura e gravado.</b> Não é uma frase de
 * interface: é uma data por chamado. Se a política mudar amanhã, o chamado de
 * hoje mantém a promessa de hoje — e dá para medir depois se ela foi cumprida,
 * que é a única coisa que transforma "prazo" em compromisso.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportTicketService {

    /**
     * Dias ÚTEIS de prazo.
     *
     * <p>Dois: é o que uma pessoa só consegue cumprir, e prometer menos seria
     * gerar o chamado seguinte ("ninguém respondeu"). Úteis, e não corridos,
     * porque um chamado aberto na sexta à noite com prazo de domingo é uma
     * promessa desenhada para ser quebrada.
     */
    static final int PRAZO_DIAS_UTEIS = 2;

    /**
     * Quantos chamados em aberto por pessoa.
     *
     * <p>Três. Não é regra de negócio, é rede contra o toque repetido de quem
     * está ansioso: cinco chamados idênticos atrasam a resposta de todo mundo,
     * inclusive a dela.
     */
    static final int MAX_ABERTOS = 3;

    /** Teto do texto. O suficiente para descrever; curto o bastante para ler. */
    static final int MAX_MENSAGEM = 2000;

    private final SupportTicketRepository ticketRepository;
    private final UserRepository userRepository;

    @Transactional
    public Ticket open(String email, SupportTicket.Subject subject, String message,
                       String appVersion, String screen) {
        User user = requireUser(email);

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Conte o que aconteceu — sem isso ninguém consegue ajudar");
        }
        String texto = message.trim();
        if (texto.length() > MAX_MENSAGEM) {
            throw new IllegalArgumentException(
                    "A mensagem passou de " + MAX_MENSAGEM + " caracteres. Se precisar de mais, "
                            + "conte o essencial aqui e complemente na resposta");
        }
        if (ticketRepository.countByUserIdAndStatus(user.getId(), SupportTicket.Status.OPEN)
                >= MAX_ABERTOS) {
            throw new IllegalArgumentException(
                    "Você já tem " + MAX_ABERTOS + " chamados aguardando resposta. Eles não se "
                            + "perderam — abra a lista para acompanhar");
        }

        SupportTicket ticket = ticketRepository.save(SupportTicket.builder()
                .userId(user.getId())
                .subject(subject != null ? subject : SupportTicket.Subject.OUTRO)
                .message(texto)
                .status(SupportTicket.Status.OPEN)
                .respondBy(prazoDe(OffsetDateTime.now()))
                .appVersion(recorte(appVersion, 20))
                .screen(recorte(screen, 40))
                .build());

        log.info("Chamado de suporte aberto: assunto={} prazo={}, user={}",
                ticket.getSubject(), ticket.getRespondBy(), user.getId());
        return toTicket(ticket);
    }

    /** O histórico — é ele que prova que o chamado não sumiu. */
    @Transactional(readOnly = true)
    public List<Ticket> listFor(String email) {
        User user = requireUser(email);
        return ticketRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(SupportTicketService::toTicket)
                .toList();
    }

    /**
     * A pessoa encerra o próprio chamado.
     *
     * <p>Encerrar é dela, não nosso: quem resolveu sozinho não deveria precisar
     * esperar alguém fechar. O chamado continua no histórico — some seria
     * perder a prova de que ela pediu ajuda.
     */
    @Transactional
    public Ticket close(String email, UUID id) {
        User user = requireUser(email);
        SupportTicket ticket = ticketRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Chamado não encontrado"));

        if (ticket.getStatus() != SupportTicket.Status.CLOSED) {
            ticket.setStatus(SupportTicket.Status.CLOSED);
            ticketRepository.save(ticket);
        }
        return toTicket(ticket);
    }

    /**
     * O prazo em dias ÚTEIS.
     *
     * <p>Sábado e domingo não contam. Um chamado aberto na sexta à noite com
     * prazo de domingo é uma promessa desenhada para ser quebrada.
     */
    static OffsetDateTime prazoDe(OffsetDateTime abertura) {
        OffsetDateTime prazo = abertura;
        int restantes = PRAZO_DIAS_UTEIS;
        while (restantes > 0) {
            prazo = prazo.plusDays(1);
            DayOfWeek dia = prazo.getDayOfWeek();
            if (dia != DayOfWeek.SATURDAY && dia != DayOfWeek.SUNDAY) restantes--;
        }
        return prazo;
    }

    private static String recorte(String valor, int teto) {
        if (valor == null || valor.isBlank()) return null;
        String limpo = valor.trim();
        return limpo.length() <= teto ? limpo : limpo.substring(0, teto);
    }

    private static Ticket toTicket(SupportTicket t) {
        return new Ticket(t.getId(), t.getSubject(), t.getMessage(), t.getStatus(),
                t.getRespondBy(), t.getAnswer(), t.getAnsweredAt(), t.isOverdue(),
                t.getCreatedAt());
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }

    /**
     * @param respondBy até quando prometemos responder
     * @param overdue   o prazo passou sem resposta — a tela precisa poder dizer
     *                  isso em vez de fingir que está tudo no rumo
     */
    public record Ticket(UUID id, SupportTicket.Subject subject, String message,
                         SupportTicket.Status status, OffsetDateTime respondBy,
                         String answer, OffsetDateTime answeredAt, boolean overdue,
                         OffsetDateTime createdAt) {
    }
}
