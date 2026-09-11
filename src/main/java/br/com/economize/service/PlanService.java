package br.com.economize.service;

import br.com.economize.config.PlanProperties;
import br.com.economize.dto.plan.PlansResponse;
import br.com.economize.model.Plan;
import br.com.economize.model.PlanInterest;
import br.com.economize.model.User;
import br.com.economize.repository.PlanInterestRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Planos da conta (V23): o que existe para oferecer, em qual o usuário está e
 * quem já disse que pagaria pelo pago.
 *
 * <p>Ainda não há cobrança. O que este serviço faz hoje é a parte que não
 * depende dela: anunciar a oferta com o preço/vantagens configurados e
 * registrar interesse — o dado que decide se vale construir o pagamento.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanService {

    private final UserRepository userRepository;
    private final PlanInterestRepository interestRepository;
    private final PlanProperties properties;

    public PlansResponse describe(String email) {
        User user = requireUser(email);
        // vigente, e não o gravado: PLUS vencido é FREE para quem pergunta o
        // que oferecer — a coluna continua PLUS para o histórico
        Plan current = user.isPlus() ? Plan.PLUS : Plan.FREE;
        boolean interested = interestRepository.existsByUserIdAndPlan(user.getId(), Plan.PLUS);
        return new PlansResponse(
                current,
                List.of(option(Plan.FREE, properties.getFree()), option(Plan.PLUS, properties.getPlus())),
                properties.isCheckoutAvailable(),
                interested,
                user.getPlanUntil(),
                user.getPlanCancelledAt());
    }

    /**
     * Registra o interesse. Idempotente: repetir não cria linha nem falha. O
     * {@code exists} poupa o insert no caso comum; quem garante a unicidade no
     * duplo toque é o unique (user_id, plan) — por isso o saveAndFlush, para a
     * violação estourar AQUI e não no commit, fora do alcance do catch.
     */
    public void registerInterest(String email, Plan plan) {
        User user = requireUser(email);
        if (interestRepository.existsByUserIdAndPlan(user.getId(), plan)) {
            return;
        }
        try {
            interestRepository.saveAndFlush(PlanInterest.builder().user(user).plan(plan).build());
            log.info("Interesse no plano {} registrado para user={}", plan, user.getId());
        } catch (DataIntegrityViolationException race) {
            // dois toques simultâneos: o outro gravou entre o exists e o insert.
            // Mesmo resultado para quem chamou — o interesse está registrado
            log.debug("Interesse no plano {} já registrado por requisição concorrente (user={})",
                    plan, user.getId());
        }
    }

    /**
     * Cancela a renovação do plano pago — EC-208.
     *
     * <p><b>O acesso NÃO é cortado na hora.</b> Quem pagou até o dia 20 usa
     * até o dia 20: cancelar encerra a renovação, não o que já foi pago.
     * Cortar na hora seria ficar com o dinheiro e tirar o serviço.
     *
     * <p><b>Idempotente.</b> Cancelar de novo devolve a mesma resposta em vez
     * de erro — quem toca duas vezes está inseguro, e um erro na segunda
     * confirma exatamente o medo que motivou o segundo toque.
     *
     * <p><b>Sem prazo conhecido, a frase é outra.</b> Um PLUS concedido à mão
     * não tem cobrança para parar. Dizer "cancelado, a cobrança para em X"
     * seria inventar uma data e uma cobrança que não existem.
     */
    @Transactional
    public CancelOutcome cancel(String email) {
        User user = requireUser(email);

        if (!user.isPlus()) {
            throw new IllegalArgumentException(
                    "Sua conta já está no plano gratuito — não há assinatura para cancelar");
        }

        if (user.getPlanCancelledAt() == null) {
            user.setPlanCancelledAt(OffsetDateTime.now());
            userRepository.save(user);
            log.info("Plano cancelado pelo usuário, acesso até {}, user={}",
                    user.getPlanUntil(), user.getId());
        }

        return new CancelOutcome(user.getPlanCancelledAt(), user.getPlanUntil(),
                mensagemDoCancelamento(user));
    }

    /**
     * A frase que a tela mostra. Ela existe no servidor porque depende de
     * regras de plano que o app não deve reimplementar — e porque a diferença
     * entre "a cobrança para em 20/10" e "não há cobrança" é a diferença entre
     * um usuário tranquilo e um e-mail de suporte.
     */
    private static String mensagemDoCancelamento(User user) {
        if (user.getPlanUntil() == null) {
            return "Cancelado. Este acesso não tinha cobrança associada, então não há nada "
                    + "para parar — e ele continua valendo.";
        }
        return "Cancelado. Nenhuma cobrança nova será feita, e o Plus continua valendo até "
                + user.getPlanUntil().toLocalDate() + ".";
    }

    /**
     * @param activeUntil até quando o acesso pago continua valendo; nulo quando
     *                    não havia prazo
     * @param message     a frase pronta, para a tela não recompor a regra
     */
    public record CancelOutcome(OffsetDateTime cancelledAt, OffsetDateTime activeUntil,
                                String message) {
    }

    private static PlansResponse.PlanOption option(Plan plan, PlanProperties.Option option) {
        return new PlansResponse.PlanOption(plan, option.getName(), option.getPriceMonthly(),
                option.getFeatures());
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }
}
