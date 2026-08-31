package br.com.economize.service.recurrence;

import br.com.economize.dto.recurrence.CreateRecurringSeriesRequest;
import br.com.economize.dto.recurrence.RecurringSeriesResponse;
import br.com.economize.dto.recurrence.UpdateRecurringSeriesRequest;
import br.com.economize.exception.ResourceConflictException;
import br.com.economize.model.Category;
import br.com.economize.model.RecurringSeries;
import br.com.economize.model.User;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.RecurringSeriesLinkRepository;
import br.com.economize.repository.RecurringSeriesRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consulta e gestão das séries recorrentes. A escrita fica em métodos
 * {@code @Transactional} deste bean, chamados via proxy de dentro de
 * {@code Mono.fromCallable().subscribeOn(boundedElastic())} no controller.
 */
@Service
@RequiredArgsConstructor
public class RecurringSeriesService {

    private final RecurringSeriesRepository seriesRepository;
    private final RecurringSeriesLinkRepository linkRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    /**
     * Séries com o próximo vencimento estimado. Sem filtro, INTERNAL fica de
     * fora: movimentação do titular entre os próprios bancos não é gasto nem
     * renda — só aparece quando pedida explicitamente com {@code ?flow=INTERNAL}.
     * Sem {@code ?active=}, só ativas não descartadas (o comportamento original);
     * {@code ?active=false} expõe as inativas — inclusive as descartadas, que de
     * outra forma ficariam invisíveis e irrecuperáveis via API.
     */
    public List<RecurringSeriesResponse> list(String email, String flowParam, Boolean active) {
        User user = requireUser(email);
        RecurringSeries.Flow flow = parseFlow(flowParam);
        return seriesRepository.findAllByUserId(user.getId()).stream()
                .filter(series -> active == null
                        ? series.isActive() && !series.isDismissed()
                        : series.isActive() == active)
                .filter(series -> flow == null
                        ? series.getFlow() != RecurringSeries.Flow.INTERNAL
                        : series.getFlow() == flow)
                .map(series -> RecurringSeriesResponse.from(series, nextDueDate(series)))
                .sorted(Comparator.comparing(RecurringSeriesResponse::nextDueDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(RecurringSeriesResponse::displayName,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Agendamento manual (EC-096): o usuário declara um gasto fixo ou renda que
     * o extrato ainda não provou. A série nasce {@code source=USER} com a chave
     * derivada pelo MESMO extrator da detecção ({@link MerchantKeyExtractor}) —
     * é isso que permite à varredura conciliar transações reais futuras com o
     * agendamento, sem caminho especial de matching.
     *
     * <p>Colisão de chave responde 409 com o id da série existente — tanto a
     * detectada na consulta prévia quanto a que uma varredura simultânea
     * materializou entre a consulta e o insert.
     */
    @Transactional
    public RecurringSeriesResponse create(String email, CreateRecurringSeriesRequest request) {
        User user = requireUser(email);
        RecurringSeries.Flow flow = parseScheduleFlow(request.flow());
        RecurringSeries.Cadence cadence = parseScheduleCadence(request.cadence());

        Short anchorDay = request.anchorDay() != null ? request.anchorDay().shortValue() : null;
        requireAnchorConsistency(cadence, anchorDay);

        LocalDate startsAt = request.startsAt() != null ? request.startsAt() : LocalDate.now();
        if (request.endsAt() != null && request.endsAt().isBefore(startsAt)) {
            throw new IllegalArgumentException("endsAt não pode ser anterior a startsAt");
        }

        UUID categoryId = null;
        if (request.categoryId() != null) {
            categoryId = categoryRepository.findAccessible(request.categoryId(), user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Categoria não encontrada"))
                    .getId();
        }

        String hintSource = request.matchHint() != null && !request.matchHint().isBlank()
                ? request.matchHint()
                : request.displayName();
        String merchantKey = MerchantKeyExtractor.deriveKey(hintSource);
        if (merchantKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Não foi possível derivar uma chave de conciliação; informe um matchHint com o nome da cobrança");
        }

        seriesRepository.findByUserIdAndMerchantKeyAndFlow(user.getId(), merchantKey, flow)
                .ifPresent(existing -> {
                    // o id viaja no ProblemDetail: sem ele o app só sabe repetir a
                    // mensagem, e "edite-a ou reative-a" vira uma instrução que o
                    // usuário tem que executar procurando a série na mão
                    throw new ResourceConflictException(String.format(
                            "Já existe uma série recorrente para esta cobrança: \"%s\" (chave \"%s\"). "
                                    + "Edite-a ou reative-a em vez de criar outra.",
                            existing.getDisplayName() != null ? existing.getDisplayName()
                                    : existing.getMerchantKey(),
                            merchantKey),
                            Map.of("seriesId", existing.getId()));
                });

        RecurringSeries series = RecurringSeries.builder()
                .user(user)
                .merchantKey(merchantKey)
                .displayName(request.displayName().trim())
                .categoryId(categoryId)
                .flow(flow)
                .cadence(cadence)
                .anchorDay(anchorDay)
                .amountType(request.amountType() == null || request.amountType().isBlank()
                        ? RecurringSeries.AmountType.FIXED
                        : parseAmountType(request.amountType()))
                .expectedAmount(request.expectedAmount())
                .occurrences(0)
                .active(true)
                .dismissed(false)
                .source(RecurringSeries.Source.USER)
                .startsAt(startsAt)
                .endsAt(request.endsAt())
                .build();

        try {
            // saveAndFlush: a violação de unique tem que estourar AQUI para virar
            // 409. Deixada para o flush do commit, ela aconteceria no proxy da
            // transação, fora do alcance deste catch, e o cliente veria 500
            seriesRepository.saveAndFlush(series);
        } catch (DataIntegrityViolationException race) {
            // corrida com a varredura pós-importação: entre a checagem acima e o
            // insert, o listener materializou a mesma (usuário, chave, fluxo). O
            // id da vencedora não pode ser lido aqui — a transação já está
            // condenada ao rollback e qualquer consulta nela é inválida
            throw new ResourceConflictException(String.format(
                    "Já existe uma série recorrente para a chave \"%s\": ela acabou de ser criada pela "
                            + "varredura automática. Recarregue a lista e edite a série existente.",
                    merchantKey));
        }
        return RecurringSeriesResponse.from(series, nextDueDate(series));
    }

    @Transactional
    public RecurringSeriesResponse update(String email, UUID id, UpdateRecurringSeriesRequest request) {
        User user = requireUser(email);
        RecurringSeries series = seriesRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Série recorrente não encontrada"));

        if (request.displayName() != null) {
            if (request.displayName().isBlank()) {
                throw new IllegalArgumentException("Nome de exibição não pode ser vazio");
            }
            series.setDisplayName(request.displayName().trim());
        }
        if (request.categoryId() != null) {
            Category category = categoryRepository.findAccessible(request.categoryId(), user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Categoria não encontrada"));
            series.setCategoryId(category.getId());
        }
        if (request.active() != null) {
            series.setActive(request.active());
            // reativar à mão desfaz o descarte: o usuário mudou de ideia e a
            // série volta ao ciclo normal (inclusive reativação automática)
            if (request.active()) {
                series.setDismissed(false);
            }
        }
        boolean statsCurated = false;
        if (request.amountType() != null) {
            series.setAmountType(parseAmountType(request.amountType()));
            statsCurated = true;
        }
        if (request.expectedAmount() != null) {
            series.setExpectedAmount(request.expectedAmount());
            statsCurated = true;
        }
        // Ritmo da cobrança: validado sobre o estado RESULTANTE, não sobre o
        // payload — trocar para WEEKLY sem mandar anchorDay zera a âncora antiga
        // (semana não tem dia do mês), e sair de WEEKLY sem informar o dia é
        // recusado em vez de gerar série sem data de projeção.
        if (request.cadence() != null || request.anchorDay() != null) {
            RecurringSeries.Cadence cadence = request.cadence() != null
                    ? parseScheduleCadence(request.cadence())
                    : series.getCadence();
            // Short.valueOf e não shortValue(): ternário com um lado primitivo
            // desembrulha o outro, e série sem âncora (WEEKLY) estouraria NPE
            Short anchorDay = request.anchorDay() != null
                    ? Short.valueOf(request.anchorDay().shortValue())
                    : series.getAnchorDay();
            if (cadence == RecurringSeries.Cadence.WEEKLY && request.anchorDay() == null) {
                anchorDay = null;
            }
            requireAnchorConsistency(cadence, anchorDay);
            series.setCadence(cadence);
            series.setAnchorDay(anchorDay);
            statsCurated = true;
        }
        // Valor/tipo/ritmo editados à mão são curadoria e não podem ser
        // recalculados pela próxima varredura: promover a USER congela os campos
        // estatísticos (a varredura continua registrando evidência:
        // occurrences/first/last). Nome e categoria não precisam disso — já são
        // protegidos um a um.
        if (statsCurated && series.getSource() == RecurringSeries.Source.DETECTED) {
            series.setSource(RecurringSeries.Source.USER);
        }
        // Vigência: não é campo estatístico (a varredura nunca a escreve), então
        // editá-la não promove a série. Só o par resultante precisa fazer
        // sentido — patch de startsAt sozinho não pode passar por cima de um
        // endsAt anterior já gravado.
        if (request.startsAt() != null || request.endsAt() != null) {
            LocalDate startsAt = request.startsAt() != null ? request.startsAt() : series.getStartsAt();
            LocalDate endsAt = request.endsAt() != null ? request.endsAt() : series.getEndsAt();
            if (startsAt != null && endsAt != null && endsAt.isBefore(startsAt)) {
                throw new IllegalArgumentException("endsAt não pode ser anterior a startsAt");
            }
            series.setStartsAt(startsAt);
            series.setEndsAt(endsAt);
        }

        seriesRepository.save(series);
        return RecurringSeriesResponse.from(series, nextDueDate(series));
    }

    /**
     * Excluir é DESCARTAR ({@code active=false} + {@code dismissed}): apagar
     * faria a próxima varredura recriar a série do zero, e a marca de descarte
     * impede a ressurreição quando chega cobrança nova — o motor nunca desfaz um
     * dismiss, só {@code PATCH active=true}. A única exceção é a série de origem
     * USER sem nenhuma transação casada: não há histórico a preservar para o
     * EC-096 nem chave que a varredura recriaria, então sai do banco de verdade.
     */
    @Transactional
    public boolean delete(String email, UUID id) {
        User user = requireUser(email);
        RecurringSeries series = seriesRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Série recorrente não encontrada"));

        if (series.getSource() == RecurringSeries.Source.USER
                && !linkRepository.existsBySeriesId(series.getId())) {
            seriesRepository.delete(series);
            return true;
        }
        series.setActive(false);
        series.setDismissed(true);
        seriesRepository.save(series);
        return false;
    }


    /**
     * Teto da corrente de vencimentos: ~50 anos de ciclos mensais. Série antiga
     * e parada teria o próximo vencimento muito no passado, e sem o teto o
     * avanço até a janela pedida seria um laço sem fim.
     */
    private static final int MAX_DUE_STEPS = 600;

    /** Uma despesa prevista para vencer dentro de uma janela (EC-136). */
    public record UpcomingDue(
            UUID seriesId,
            String name,
            UUID categoryId,
            LocalDate dueDate,
            java.math.BigDecimal amount,
            /* conta de consumo: o valor é a média do histórico, não um boleto */
            boolean estimated
    ) {
    }

    /**
     * As despesas recorrentes que vencem entre duas datas (EC-136).
     *
     * <p>Serve à pergunta "quando o salário cair, quanto já tem dono": não é
     * previsão estatística, são os boletos, assinaturas e faturas que o motor
     * de recorrência já provou no extrato.
     *
     * <p>A corrente de vencimentos é a MESMA do {@code nextDueDate} da listagem
     * e da previsão de saldo. Derivar a data da âncora do calendário faria a
     * cobrança que deslizou para o começo do mês seguinte ser contada duas
     * vezes — e as três telas discordariam entre si sobre o mesmo boleto.
     *
     * <p>IRREGULAR fica de fora: sem cadência estimável não há vencimento a
     * prever, e chutar um comprometeria o número que o usuário vai usar para
     * decidir se pode gastar.
     */
    public List<UpcomingDue> upcomingExpenses(UUID userId, LocalDate from, LocalDate to) {
        List<UpcomingDue> out = new ArrayList<>();
        if (from == null || to == null || to.isBefore(from)) return out;

        for (RecurringSeries series : seriesRepository.findAllByUserId(userId)) {
            if (series.getFlow() != RecurringSeries.Flow.EXPENSE) continue;
            if (!series.isActive() || series.isDismissed()) continue;
            if (series.getCadence() == RecurringSeries.Cadence.IRREGULAR) continue;
            if (series.getExpectedAmount() == null) continue;

            LocalDate due = nextDueDate(series);
            int steps = 0;
            while (due != null && !due.isAfter(to) && steps++ < MAX_DUE_STEPS) {
                if (!due.isBefore(from)) {
                    out.add(new UpcomingDue(
                            series.getId(),
                            series.getDisplayName() != null
                                    ? series.getDisplayName() : series.getMerchantKey(),
                            series.getCategoryId(),
                            due,
                            series.getExpectedAmount().abs(),
                            series.getAmountType() == RecurringSeries.AmountType.VARIABLE));
                }
                due = dueAfter(series, due);
            }
        }
        out.sort(Comparator.comparing(UpcomingDue::dueDate));
        return out;
    }

    /**
     * Próximo vencimento = última ocorrência + um ciclo, ajustado ao dia âncora
     * mais próximo — é assim que a cobrança do dia 30 que caiu no dia 02 volta
     * a ser prevista para o comecinho do mês, não para o dia 02 de todo mês.
     */
    static LocalDate nextDueDate(RecurringSeries series) {
        if (series.getLastSeenAt() == null) return capAtEnd(firstScheduledDue(series), series.getEndsAt());
        return dueAfter(series, series.getLastSeenAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate());
    }

    /**
     * Vencimento seguinte a uma ocorrência — real (última conciliada) ou já
     * projetada. É o passo que a previsão de saldo encadeia para descobrir em
     * QUAIS meses a série vence: derivar o mês da âncora do calendário faria a
     * cobrança que deslizou para o começo do mês seguinte ser contada duas
     * vezes, e a previsão discordaria do nextDueDate da própria listagem.
     */
    static LocalDate dueAfter(RecurringSeries series, LocalDate previous) {
        LocalDate due = switch (series.getCadence()) {
            case WEEKLY -> previous.plusDays(7);
            case MONTHLY -> snapToAnchor(previous.plusMonths(1), series.getAnchorDay(), previous);
            case QUARTERLY -> snapToAnchor(previous.plusMonths(3), series.getAnchorDay(), previous);
            case IRREGULAR -> null;
        };
        return capAtEnd(due, series.getEndsAt());
    }

    /**
     * Série agendada que ainda não conciliou nenhuma transação: o próximo
     * vencimento sai da vigência — a primeira data a partir de startsAt que cai
     * no dia âncora (WEEKLY vence no próprio startsAt, escolhido pelo usuário).
     */
    private static LocalDate firstScheduledDue(RecurringSeries series) {
        LocalDate start = series.getStartsAt();
        if (start == null) return null;
        return switch (series.getCadence()) {
            case WEEKLY -> start;
            case MONTHLY, QUARTERLY -> {
                if (series.getAnchorDay() == null) yield start;
                YearMonth month = YearMonth.from(start);
                LocalDate candidate = month.atDay(Math.min(series.getAnchorDay(), month.lengthOfMonth()));
                if (candidate.isBefore(start)) {
                    // a primeira cobrança não pode preceder o início da vigência
                    YearMonth next = month.plusMonths(
                            series.getCadence() == RecurringSeries.Cadence.MONTHLY ? 1 : 3);
                    candidate = next.atDay(Math.min(series.getAnchorDay(), next.lengthOfMonth()));
                }
                yield candidate;
            }
            case IRREGULAR -> null;
        };
    }

    // agendamento com fim (parcelamento/contrato): depois do endsAt não há
    // próximo vencimento a mostrar
    private static LocalDate capAtEnd(LocalDate due, LocalDate endsAt) {
        if (due == null) return null;
        if (endsAt != null && due.isAfter(endsAt)) return null;
        return due;
    }

    private static LocalDate snapToAnchor(LocalDate candidate, Short anchorDay, LocalDate mustBeAfter) {
        if (anchorDay == null) return candidate;
        LocalDate best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int offset = -1; offset <= 1; offset++) {
            YearMonth month = YearMonth.from(candidate).plusMonths(offset);
            LocalDate option = month.atDay(Math.min(anchorDay, month.lengthOfMonth()));
            if (!option.isAfter(mustBeAfter)) continue;
            long distance = Math.abs(ChronoUnit.DAYS.between(candidate, option));
            if (best == null || distance < bestDistance) {
                best = option;
                bestDistance = distance;
            }
        }
        return best != null ? best : candidate;
    }

    /**
     * WEEKLY não tem dia do mês; nas cadências com ciclo mensal o dia âncora é o
     * que dá data à projeção — sem ele o agendamento seria um valor solto no mês.
     */
    private void requireAnchorConsistency(RecurringSeries.Cadence cadence, Short anchorDay) {
        boolean monthAnchored = cadence == RecurringSeries.Cadence.MONTHLY
                || cadence == RecurringSeries.Cadence.QUARTERLY;
        if (!monthAnchored) {
            if (anchorDay != null) {
                throw new IllegalArgumentException("Dia âncora não se aplica à cadência " + cadence);
            }
        } else if (anchorDay == null) {
            throw new IllegalArgumentException("Dia âncora é obrigatório para cadência MONTHLY/QUARTERLY");
        }
    }

    // O agendamento manual só aceita gasto ou renda: INTERNAL é uma conclusão
    // da detecção (dinheiro do titular circulando), não algo que se agenda
    private RecurringSeries.Flow parseScheduleFlow(String flow) {
        try {
            RecurringSeries.Flow parsed =
                    RecurringSeries.Flow.valueOf(flow.trim().toUpperCase(java.util.Locale.ROOT));
            if (parsed == RecurringSeries.Flow.INTERNAL) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Fluxo inválido para agendamento: use EXPENSE ou INCOME");
        }
    }

    // IRREGULAR é um veredito da detecção ("não achei ciclo"), não uma cadência
    // agendável — sem ciclo não existiria data nem projeção
    private RecurringSeries.Cadence parseScheduleCadence(String cadence) {
        try {
            RecurringSeries.Cadence parsed =
                    RecurringSeries.Cadence.valueOf(cadence.trim().toUpperCase(java.util.Locale.ROOT));
            if (parsed == RecurringSeries.Cadence.IRREGULAR) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Cadência inválida: use MONTHLY, WEEKLY ou QUARTERLY");
        }
    }

    private RecurringSeries.Flow parseFlow(String flowParam) {
        if (flowParam == null || flowParam.isBlank()) return null;
        try {
            return RecurringSeries.Flow.valueOf(flowParam.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Fluxo inválido: use EXPENSE, INCOME ou INTERNAL");
        }
    }

    private RecurringSeries.AmountType parseAmountType(String amountType) {
        try {
            return RecurringSeries.AmountType.valueOf(amountType.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Tipo de valor inválido: use FIXED ou VARIABLE");
        }
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }
}
