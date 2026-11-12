package br.com.economize.service.wish;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.User;
import br.com.economize.model.Wish;
import br.com.economize.model.WishContribution;
import br.com.economize.repository.UserRepository;
import br.com.economize.repository.WishContributionRepository;
import br.com.economize.repository.WishRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Guardar dinheiro numa meta, com rastro — EC-205.
 *
 * <p><b>O defeito que isto fecha.</b> O progresso de uma meta era um campo que
 * a pessoa digitava. Ele dizia "R$ 1.200 juntados" e não dizia quando, nem de
 * quanto foi cada entrada, nem como desfazer um dedo errado. É a mesma falta
 * que o EC-202 corrigiu nas varreduras: número que muda sem deixar recado.
 *
 * <p><b>A sobra do ciclo é a proposta, não o comando.</b> O app sabe quanto
 * sobrou no ciclo — é a mesma medida que alimenta a projeção do desejo. Mas
 * ele NÃO move esse dinheiro sozinho: a pessoa decide se guardou mesmo, e
 * quanto. Um app que mexesse no progresso de uma meta por conta própria
 * estaria afirmando um fato sobre a vida de alguém que ele não tem como saber.
 *
 * <p><b>Medido e declarado não se misturam.</b> Um aporte que veio da sobra
 * apurada carrega {@code MEASURED}; um número digitado carrega
 * {@code DECLARED}. A tela mostra a diferença, e é a mesma regra que o EC-206
 * pede da previsão: nunca apresentar como medido o que foi informado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WishContributionService {

    /**
     * O maior saldo que a coluna comporta — {@code saved_amount} é
     * {@code NUMERIC(19,4)}, ou seja, quinze dígitos inteiros e quatro
     * decimais. Não é regra de negócio: é o limite físico, escrito aqui para
     * virar um 400 explicado em vez de um 500 sem explicação.
     */
    static final BigDecimal SALDO_MAXIMO = new BigDecimal("999999999999999.9999");
    private static final List<Wish.Status> STATUS_ENCERRADOS = List.of(
            Wish.Status.PURCHASED, Wish.Status.ARCHIVED);

    private final WishContributionRepository contributionRepository;
    private final WishRepository wishRepository;
    private final UserRepository userRepository;

    /**
     * Registra um aporte e atualiza o saldo da meta.
     *
     * @param cycleMonth quando presente ({@code YYYY-MM}), marca o aporte como
     *                   MEDIDO e trava o ciclo: a sobra de setembro entra uma
     *                   vez só, por mais vezes que alguém toque no botão
     */
    @Transactional
    public Result contribute(String email, UUID wishId, BigDecimal amount,
                             String cycleMonth, String note) {
        User user = requireUser(email);
        Wish wish = requireWish(user.getId(), wishId);

        if (amount == null || amount.signum() == 0) {
            throw new IllegalArgumentException(
                    "Um aporte precisa de valor. Para corrigir um erro, lance o valor negativo — "
                            + "ele sai do saldo e fica no histórico");
        }
        if (wish.getStatus() == Wish.Status.PURCHASED
                || wish.getStatus() == Wish.Status.ARCHIVED) {
            throw new IllegalArgumentException(
                    "Esta meta já foi encerrada; guardar dinheiro nela não muda mais nada");
        }

        String ciclo = normalizarCiclo(cycleMonth);
        if (ciclo != null) {
            contributionRepository.findByWishIdAndCycleMonth(wishId, ciclo).ifPresent(ja -> {
                // A frase importa: "já guardei" é diferente de "deu erro", e o
                // usuário precisa saber que o dinheiro NÃO entrou duas vezes
                throw new IllegalArgumentException(
                        "A sobra de " + ciclo + " já foi guardada nesta meta");
            });
        }

        // O saldo não pode ficar negativo: devolver mais do que se guardou é
        // aritmética que não corresponde a nada na vida da pessoa
        BigDecimal saldoAtual = wish.getSavedAmount() == null ? BigDecimal.ZERO : wish.getSavedAmount();
        BigDecimal novoSaldo = saldoAtual.add(amount);
        if (novoSaldo.signum() < 0) {
            throw new IllegalArgumentException(
                    "Não dá para devolver mais do que está guardado nesta meta");
        }
        // O teto não é capricho: `saved_amount` é NUMERIC(19,4), e a SOMA pode
        // estourar mesmo com cada aporte cabendo sozinho — dois de quinze
        // dígitos dão dezesseis. Sem esta linha o Postgres recusava e a pessoa
        // recebia "erro inesperado, tente novamente mais tarde", que é mentira:
        // tentar mais tarde dá exatamente igual
        if (novoSaldo.compareTo(SALDO_MAXIMO) > 0) {
            throw new IllegalArgumentException(
                    "Este aporte passa do maior valor que uma meta comporta");
        }

        // O saldo precisa mudar na própria linha: dois aportes simultâneos não
        // podem sobrescrever um ao outro depois de ler o mesmo valor anterior.
        if (wishRepository.adjustSavedAmount(wishId, user.getId(), amount, SALDO_MAXIMO,
                STATUS_ENCERRADOS) != 1) {
            recusarAjusteQueMudouEnquantoEraProcessado(user.getId(), wishId, amount);
        }

        WishContribution aporte = contributionRepository.save(WishContribution.builder()
                .wishId(wishId)
                .userId(user.getId())
                .amount(amount)
                .origin(ciclo != null
                        ? WishContribution.Origin.MEASURED
                        : WishContribution.Origin.DECLARED)
                .cycleMonth(ciclo)
                .note(note != null && !note.isBlank() ? note.trim() : null)
                .build());

        log.info("Aporte em meta: {} origem={} ciclo={}, user={}",
                amount, aporte.getOrigin(), ciclo, user.getId());
        // O saldo da resposta é RELIDO depois do UPDATE: o calculado lá em cima
        // partiu do valor lido antes, e com outro aporte no meio ele diria
        // R$ 100 com R$ 150 gravados. O clearAutomatically do UPDATE faz esta
        // leitura vir do banco, não da entidade já carregada
        Wish gravada = requireWish(user.getId(), wishId);
        BigDecimal saldoGravado = gravada.getSavedAmount() == null
                ? BigDecimal.ZERO : gravada.getSavedAmount();
        return new Result(toItem(aporte), saldoGravado);
    }

    /**
     * A guarda do UPDATE pode falhar porque outra requisição chegou antes.
     * Relê o estado para manter as mesmas recusas da validação inicial.
     */
    private void recusarAjusteQueMudouEnquantoEraProcessado(UUID userId, UUID wishId,
                                                              BigDecimal amount) {
        Wish atual = requireWish(userId, wishId);
        if (STATUS_ENCERRADOS.contains(atual.getStatus())) {
            throw new IllegalArgumentException(
                    "Esta meta já foi encerrada; guardar dinheiro nela não muda mais nada");
        }
        BigDecimal saldo = atual.getSavedAmount() == null ? BigDecimal.ZERO : atual.getSavedAmount();
        BigDecimal novoSaldo = saldo.add(amount);
        if (novoSaldo.signum() < 0) {
            throw new IllegalArgumentException(
                    "Não dá para devolver mais do que está guardado nesta meta");
        }
        if (novoSaldo.compareTo(SALDO_MAXIMO) > 0) {
            throw new IllegalArgumentException(
                    "Este aporte passa do maior valor que uma meta comporta");
        }
        throw new IllegalArgumentException("A meta mudou enquanto o aporte era processado; tente novamente");
    }

    /** O extrato da meta — o que explica o saldo dela. */
    @Transactional(readOnly = true)
    public List<Item> historyFor(String email, UUID wishId) {
        User user = requireUser(email);
        requireWish(user.getId(), wishId);
        return contributionRepository.findAllByWishIdOrderByCreatedAtDesc(wishId).stream()
                .map(WishContributionService::toItem)
                .toList();
    }

    /**
     * Normaliza (e valida) o ciclo.
     *
     * <p>Texto torto vira 400 com o formato esperado, e não um aporte gravado
     * com um ciclo que nenhuma tela vai reconhecer depois.
     */
    private static String normalizarCiclo(String bruto) {
        if (bruto == null || bruto.isBlank()) return null;
        try {
            return YearMonth.parse(bruto.trim()).toString();
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Ciclo inválido — use o formato YYYY-MM");
        }
    }

    private static Item toItem(WishContribution c) {
        return new Item(c.getId(), c.getAmount(), c.getOrigin(), c.getCycleMonth(),
                c.getNote(), c.getCreatedAt());
    }

    private Wish requireWish(UUID userId, UUID id) {
        return wishRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Desejo não encontrado"));
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }

    /**
     * @param origin    MEASURED veio da sobra apurada; DECLARED a pessoa digitou
     * @param cycleMonth o ciclo de onde a sobra saiu, ou nulo no digitado
     */
    public record Item(UUID id, BigDecimal amount, WishContribution.Origin origin,
                       String cycleMonth, String note, OffsetDateTime createdAt) {
    }

    /** @param savedAmount o saldo da meta DEPOIS do aporte */
    public record Result(Item contribution, BigDecimal savedAmount) {
    }
}
