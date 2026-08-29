package br.com.economize.service.recurrence;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.RecurringSeries;
import br.com.economize.model.RecurringSeriesLink;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.RecurringSeriesLinkRepository;
import br.com.economize.repository.RecurringSeriesRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fixtures 100% sintéticas (nomes fictícios, valores inventados), inspiradas
 * apenas nos PADRÕES textuais que a engine precisa cobrir: rotulagem que muda,
 * adquirente instável, cadência cruzando a virada do mês, valor variável,
 * transferência interna e renda informal.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecurrenceDetectionServiceTest {

    private static final String EMAIL = "carlos@economize.dev";

    @Mock
    private UserRepository userRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private RecurringSeriesRepository seriesRepository;

    @Mock
    private RecurringSeriesLinkRepository linkRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private RecurrenceDetectionService service;

    private final User user = User.builder()
            .id(UUID.randomUUID()).name("Carlos Pereira").email(EMAIL).password("x").build();

    private final UUID utilitiesCategory = UUID.randomUUID();

    // "banco" em memória: os mocks leem e escrevem aqui, o que permite testar
    // idempotência e incrementos com o mesmo código de produção
    private final List<BankTransaction> transactions = new ArrayList<>();
    private final List<RecurringSeries> seriesStore = new ArrayList<>();
    private final List<RecurringSeriesLink> linkStore = new ArrayList<>();

    @BeforeEach
    void wireStores() {
        // o template roda o callback direto: a transação real não existe no teste
        // unitário, e os cenários de corrida a substituem para simular o commit
        // perdido (ver retriesInFreshTransaction...)
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(new SimpleTransactionStatus()));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenAnswer(inv -> new ArrayList<>(transactions));
        when(seriesRepository.findAllByUserId(user.getId()))
                .thenAnswer(inv -> new ArrayList<>(seriesStore));
        when(seriesRepository.save(any(RecurringSeries.class))).thenAnswer(inv -> {
            RecurringSeries series = inv.getArgument(0);
            if (series.getId() == null) {
                series.setId(UUID.randomUUID());
                seriesStore.add(series);
            }
            return series;
        });
        when(seriesRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(linkRepository.findAllBySeriesIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return linkStore.stream().filter(link -> ids.contains(link.getSeriesId())).toList();
        });
        when(linkRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<RecurringSeriesLink> links = inv.getArgument(0);
            linkStore.addAll(links);
            return links;
        });
    }

    @Test
    void firstRunDetectsAllRecurrenceSignals() {
        loadCoreFixtures();

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        assertThat(summary.seriesCreated()).isEqualTo(7);
        assertThat(summary.seriesUpdated()).isZero();
        assertThat(summary.linksCreated()).isEqualTo(31);
        assertThat(linkStore).hasSize(31);
        assertThat(linkStore.stream().map(RecurringSeriesLink::getBankTransactionId).distinct()).hasSize(31);

        // 1) rotulagem que muda + valor variável = conta de consumo numa série só
        RecurringSeries water = series("aquanorte", RecurringSeries.Flow.EXPENSE);
        assertThat(water.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(water.getAmountType()).isEqualTo(RecurringSeries.AmountType.VARIABLE);
        assertThat(water.getOccurrences()).isEqualTo(6);
        assertThat(water.getAnchorDay()).isEqualTo((short) 10);
        assertThat(water.getExpectedAmount()).isEqualByComparingTo("89.2667");
        assertThat(water.getCategoryId()).isEqualTo(utilitiesCategory);

        // 2) adquirente instável + virada do mês = assinatura de valor fixo
        RecurringSeries streaming = series("melodia", RecurringSeries.Flow.EXPENSE);
        assertThat(streaming.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(streaming.getAmountType()).isEqualTo(RecurringSeries.AmountType.FIXED);
        assertThat(streaming.getExpectedAmount()).isEqualByComparingTo("21.90");
        assertThat(streaming.getAnchorDay()).isEqualTo((short) 1);
        assertThat(streaming.getDayTolerance()).isEqualTo((short) 2);
        assertThat(streaming.getOccurrences()).isEqualTo(4);

        // 3) fatura ancorada pela palavra, mesmo citando o nome do titular
        RecurringSeries bill = series("fatura", RecurringSeries.Flow.EXPENSE);
        assertThat(bill.getOccurrences()).isEqualTo(4);
        assertThat(bill.getAmountType()).isEqualTo(RecurringSeries.AmountType.VARIABLE);

        // 4) troca de plano na mesma série, com a dica "Trimestral" mandando na cadência
        RecurringSeries phone = series("zetacel", RecurringSeries.Flow.EXPENSE);
        assertThat(phone.getOccurrences()).isEqualTo(5);
        assertThat(phone.getCadence()).isEqualTo(RecurringSeries.Cadence.QUARTERLY);

        // 5) movimentação do titular entre bancos = INTERNAL, nunca gasto/renda
        RecurringSeries internal = series("pereira", RecurringSeries.Flow.INTERNAL);
        assertThat(internal.getOccurrences()).isEqualTo(6);
        assertThat(find("pereira", RecurringSeries.Flow.EXPENSE)).isEmpty();
        assertThat(find("pereira", RecurringSeries.Flow.INCOME)).isEmpty();

        // 6) renda: salário fixo e PIX informal do mesmo nome todo mês
        RecurringSeries salary = series("salario", RecurringSeries.Flow.INCOME);
        assertThat(salary.getAmountType()).isEqualTo(RecurringSeries.AmountType.FIXED);
        assertThat(salary.getExpectedAmount()).isEqualByComparingTo("4500.00");
        assertThat(salary.getAnchorDay()).isEqualTo((short) 30);
        RecurringSeries informal = series("prado", RecurringSeries.Flow.INCOME);
        assertThat(informal.getOccurrences()).isEqualTo(3);

        // compras esparsas (1-2 ocorrências) não viram série
        assertThat(seriesStore).hasSize(7);
        assertThat(seriesStore).allMatch(RecurringSeries::isActive);
        assertThat(seriesStore).allMatch(s -> s.getSource() == RecurringSeries.Source.DETECTED);
    }

    @Test
    void secondRunOverSameDataChangesNothing() {
        loadCoreFixtures();
        service.detect(EMAIL);

        RecurrenceDetectionService.DetectionSummary second = service.detect(EMAIL);

        assertThat(second).isEqualTo(new RecurrenceDetectionService.DetectionSummary(0, 0, 0));
        assertThat(seriesStore).hasSize(7);
        assertThat(linkStore).hasSize(31);
    }

    @Test
    void newTransactionJoinsExistingSeriesWithoutDuplicating() {
        loadCoreFixtures();
        service.detect(EMAIL);

        transactions.add(tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-21.90", day(2025, 6, 2)));
        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        assertThat(summary.seriesCreated()).isZero();
        assertThat(summary.seriesUpdated()).isEqualTo(1);
        assertThat(summary.linksCreated()).isEqualTo(1);

        RecurringSeries streaming = series("melodia", RecurringSeries.Flow.EXPENSE);
        assertThat(streaming.getOccurrences()).isEqualTo(5);
        assertThat(streaming.getLastSeenAt().toLocalDate()).isEqualTo(LocalDate.of(2025, 6, 2));
        assertThat(seriesStore).hasSize(7);
        assertThat(linkStore).hasSize(32);
    }

    @Test
    void seriesGoesInactiveAfterTwoSilentCyclesAndIsNeverDeleted() {
        // academia detectada numa varredura antiga parou de ser cobrada em março;
        // o salário segue entrando até junho e é a referência de "hoje"
        List<BankTransaction> gym = List.of(
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 1, 10)),
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 2, 10)),
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 3, 10)));
        transactions.addAll(gym);
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 4, 30)));
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 5, 30)));
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 6, 30)));

        RecurringSeries gymSeries = fitmaxSeries(gym);
        seriesStore.add(gymSeries);
        linkGymTransactions(gymSeries, gym);

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        assertThat(summary.seriesCreated()).isEqualTo(1); // salário
        assertThat(summary.seriesUpdated()).isEqualTo(1); // academia desativada
        assertThat(gymSeries.isActive()).isFalse();
        assertThat(seriesStore).contains(gymSeries); // desativa, nunca deleta

        // e a desativação não fica "piscando": nova varredura não muda nada
        RecurrenceDetectionService.DetectionSummary again = service.detect(EMAIL);
        assertThat(again).isEqualTo(new RecurrenceDetectionService.DetectionSummary(0, 0, 0));
        assertThat(gymSeries.isActive()).isFalse();
    }

    @Test
    void userCuratedSeriesKeepsFieldsButAccumulatesEvidence() {
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 4, 30)));
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 5, 30)));
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 6, 30)));

        RecurringSeries curated = RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey("salario").flow(RecurringSeries.Flow.INCOME)
                .displayName("Salário CLT")
                .cadence(RecurringSeries.Cadence.WEEKLY)
                .amountType(RecurringSeries.AmountType.FIXED)
                .expectedAmount(new BigDecimal("9999.00"))
                .occurrences(0)
                .active(true).source(RecurringSeries.Source.USER)
                .build();
        seriesStore.add(curated);

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        assertThat(summary.seriesCreated()).isZero();
        assertThat(summary.seriesUpdated()).isEqualTo(1);
        assertThat(summary.linksCreated()).isEqualTo(3);
        // o que o usuário curou fica intacto...
        assertThat(curated.getDisplayName()).isEqualTo("Salário CLT");
        assertThat(curated.getCadence()).isEqualTo(RecurringSeries.Cadence.WEEKLY);
        assertThat(curated.getExpectedAmount()).isEqualByComparingTo("9999.00");
        // ...mas a evidência nova é registrada
        assertThat(curated.getOccurrences()).isEqualTo(3);
        assertThat(curated.getLastSeenAt()).isNotNull();
    }

    @Test
    void scheduledUserSeriesConciliatesFirstRealTransaction() {
        // agendamento manual (EC-096): chave "aluguel" derivada do displayName.
        // A 1ª transação real tem que conciliar mesmo abaixo do mínimo de 3 — e
        // mesmo que o token dominante do histórico fosse outro ("apartamento")
        RecurringSeries scheduled = RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey("aluguel").flow(RecurringSeries.Flow.EXPENSE)
                .displayName("Aluguel")
                .cadence(RecurringSeries.Cadence.MONTHLY)
                .anchorDay((short) 5)
                .amountType(RecurringSeries.AmountType.FIXED)
                .expectedAmount(new BigDecimal("1500.00"))
                .occurrences(0)
                .active(true).source(RecurringSeries.Source.USER)
                .startsAt(LocalDate.of(2025, 6, 1))
                .build();
        seriesStore.add(scheduled);
        transactions.add(tx("Pix enviado para Aluguel Apartamento 301", "DEBIT", "-1500.00",
                day(2025, 6, 5)));

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        assertThat(summary.seriesCreated()).isZero();
        assertThat(summary.seriesUpdated()).isEqualTo(1);
        assertThat(summary.linksCreated()).isEqualTo(1);
        assertThat(linkStore).hasSize(1);
        assertThat(linkStore.get(0).getSeriesId()).isEqualTo(scheduled.getId());
        // evidência registrada...
        assertThat(scheduled.getOccurrences()).isEqualTo(1);
        assertThat(scheduled.getLastSeenAt().toLocalDate()).isEqualTo(LocalDate.of(2025, 6, 5));
        // ...sem tocar na curadoria
        assertThat(scheduled.getDisplayName()).isEqualTo("Aluguel");
        assertThat(scheduled.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(scheduled.getExpectedAmount()).isEqualByComparingTo("1500.00");
        assertThat(scheduled.getAnchorDay()).isEqualTo((short) 5);
        // e nenhuma série paralela foi criada para a mesma cobrança
        assertThat(seriesStore).hasSize(1);

        // re-execução continua idempotente
        assertThat(service.detect(EMAIL))
                .isEqualTo(new RecurrenceDetectionService.DetectionSummary(0, 0, 0));
    }

    @Test
    void curatedKeyDoesNotHijackTransactionOutsideTheExpectedBand() {
        // agendamento "Conta de Luz" produz a chave genérica "luz": sem guarda,
        // qualquer PIX que cite a palavra entraria na série e marcaria o mês
        // como pago, com vínculo que nenhum endpoint desfaz
        RecurringSeries scheduled = scheduledUtilities("luz",
                RecurringSeries.AmountType.VARIABLE, "230.00");
        seriesStore.add(scheduled);
        BankTransaction bill = tx("Pagamento conta de luz Enerluz", "DEBIT", "-241.80", day(2025, 6, 12));
        BankTransaction unrelatedPix = tx("Pix enviado para Ana Luz", "DEBIT", "-60.00", day(2025, 6, 20));
        transactions.add(bill);
        transactions.add(unrelatedPix);

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        // a conta de luz de verdade (valor na banda) concilia...
        assertThat(summary.linksCreated()).isEqualTo(1);
        assertThat(linkStore).hasSize(1);
        assertThat(linkStore.get(0).getBankTransactionId()).isEqualTo(bill.getId());
        assertThat(linkStore.get(0).getSeriesId()).isEqualTo(scheduled.getId());
        assertThat(scheduled.getOccurrences()).isEqualTo(1);
        assertThat(scheduled.getLastSeenAt().toLocalDate()).isEqualTo(LocalDate.of(2025, 6, 12));
        // ...e o PIX para "Ana Luz" volta ao fluxo normal de descoberta, onde
        // uma ocorrência sozinha não vira série
        assertThat(seriesStore).hasSize(1);
        assertThat(find("ana", RecurringSeries.Flow.EXPENSE)).isEmpty();
    }

    @Test
    void curatedKeyLosesToTheTokenThatDominatesTheHistory() {
        RecurringSeries scheduled = scheduledUtilities("luz",
                RecurringSeries.AmountType.VARIABLE, "230.00");
        seriesStore.add(scheduled);
        // "supermercado" aparece em 3 meses, "luz" em um só: a compra de 235,00
        // cai na banda da conta de luz por puro acaso e mesmo assim é do mercado
        transactions.add(tx("Supermercado Bom Preco", "DEBIT", "-198.00", day(2025, 4, 8)));
        transactions.add(tx("Supermercado Bom Preco", "DEBIT", "-210.40", day(2025, 5, 8)));
        transactions.add(tx("Supermercado Luz da Manha", "DEBIT", "-235.00", day(2025, 6, 8)));

        service.detect(EMAIL);

        RecurringSeries market = series("supermercado", RecurringSeries.Flow.EXPENSE);
        assertThat(market.getOccurrences()).isEqualTo(3);
        assertThat(linkStore).hasSize(3);
        assertThat(linkStore).allMatch(link -> link.getSeriesId().equals(market.getId()));
        assertThat(scheduled.getOccurrences()).isZero();
        assertThat(scheduled.getLastSeenAt()).isNull();
    }

    @Test
    void alreadyConciliatedTransactionsSurviveAReadjustmentOutsideTheBand() {
        // assinatura agendada que reajustou de 21,90 para 29,90 (+36%): o
        // histórico já conciliado não pode ser expulso da própria série pela
        // banda do valor novo
        List<BankTransaction> paid = List.of(
                tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-21.90", day(2025, 4, 1)),
                tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-21.90", day(2025, 5, 1)));
        transactions.addAll(paid);
        transactions.add(tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-29.90", day(2025, 6, 1)));

        RecurringSeries curated = RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey("melodia").flow(RecurringSeries.Flow.EXPENSE)
                .displayName("Streaming Melodia")
                .cadence(RecurringSeries.Cadence.MONTHLY).anchorDay((short) 1)
                .amountType(RecurringSeries.AmountType.FIXED)
                .expectedAmount(new BigDecimal("29.90"))
                .occurrences(paid.size())
                .firstSeenAt(paid.get(0).getDate()).lastSeenAt(paid.get(1).getDate())
                .active(true).source(RecurringSeries.Source.USER)
                .build();
        seriesStore.add(curated);
        for (BankTransaction transaction : paid) {
            linkStore.add(RecurringSeriesLink.builder()
                    .id(UUID.randomUUID()).seriesId(curated.getId())
                    .bankTransactionId(transaction.getId()).matchedAt(OffsetDateTime.now()).build());
        }

        service.detect(EMAIL);

        assertThat(curated.getOccurrences()).isEqualTo(3);
        assertThat(linkStore).hasSize(3);
        assertThat(seriesStore).hasSize(1);
    }

    @Test
    void dismissedScheduledSeriesStopsCapturingByItsCuratedKey() {
        RecurringSeries scheduled = scheduledUtilities("luz",
                RecurringSeries.AmountType.VARIABLE, "230.00");
        scheduled.setActive(false);
        scheduled.setDismissed(true);
        seriesStore.add(scheduled);
        transactions.add(tx("Pagamento conta de luz Enerluz", "DEBIT", "-241.80", day(2025, 6, 12)));

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        // descartada, a chave "luz" perde a prioridade: a transação vai para o
        // token dominante ("enerluz") e, sozinha, não vira série nenhuma
        assertThat(summary).isEqualTo(new RecurrenceDetectionService.DetectionSummary(0, 0, 0));
        assertThat(linkStore).isEmpty();
        assertThat(scheduled.getOccurrences()).isZero();
        assertThat(scheduled.getLastSeenAt()).isNull();
        assertThat(scheduled.isDismissed()).isTrue();
    }

    @Test
    void unknownUserIsRejected() {
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detectByUserId(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usuário não encontrado");
    }

    @Test
    void retriesInFreshTransactionWhenConcurrentRunWinsTheUnique() {
        loadCoreFixtures();
        AtomicBoolean lostOnce = new AtomicBoolean(false);
        // doAnswer (e não when) para re-stubar: when() executaria o Answer do
        // setUp com argumento null durante o próprio stubbing
        doAnswer(inv -> {
            // 1ª tentativa: outra varredura (listener pós-import) commitou antes e
            // o flush desta violou o unique — o rollback desfez tudo, então o
            // callback nem chega a rodar nesta simulação
            if (lostOnce.compareAndSet(false, true)) {
                throw new DataIntegrityViolationException("uq_recurring_series_user_key_flow");
            }
            return inv.getArgument(0, TransactionCallback.class)
                    .doInTransaction(new SimpleTransactionStatus());
        }).when(transactionTemplate).execute(any());

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        assertThat(summary.seriesCreated()).isEqualTo(7);
        assertThat(summary.linksCreated()).isEqualTo(31);
        verify(transactionTemplate, times(2)).execute(any());
    }

    @Test
    void degradesToEmptySummaryWhenBothAttemptsLoseTheRace() {
        doThrow(new DataIntegrityViolationException("uq_recurring_series_user_key_flow"))
                .when(transactionTemplate).execute(any());

        // corrida em cascata: os fatos já foram materializados pela execução
        // vencedora — o endpoint manual devolve "nada novo", nunca 500
        assertThat(service.detect(EMAIL))
                .isEqualTo(new RecurrenceDetectionService.DetectionSummary(0, 0, 0));
        verify(transactionTemplate, times(2)).execute(any());
    }

    @Test
    void pixPairAloneDoesNotSwallowLegitimateExpense() {
        // diarista recebe PIX mensal; no MESMO dia entra um PIX de cliente com o
        // MESMO valor — coincidência de dia+valor não pode virar INTERNAL
        transactions.add(tx("Pix enviado para Ana Lima", "DEBIT", "-400.00", day(2025, 4, 5)));
        transactions.add(tx("Pix recebido de Cliente Xis", "CREDIT", "400.00", day(2025, 4, 5)));
        transactions.add(tx("Pix enviado para Ana Lima", "DEBIT", "-400.00", day(2025, 5, 5)));
        transactions.add(tx("Pix recebido de Cliente Xis", "CREDIT", "400.00", day(2025, 5, 5)));
        transactions.add(tx("Pix enviado para Ana Lima", "DEBIT", "-400.00", day(2025, 6, 5)));
        transactions.add(tx("Pix recebido de Cliente Xis", "CREDIT", "400.00", day(2025, 6, 5)));

        service.detect(EMAIL);

        assertThat(series("lima", RecurringSeries.Flow.EXPENSE).getOccurrences()).isEqualTo(3);
        assertThat(find("lima", RecurringSeries.Flow.INTERNAL)).isEmpty();
        assertThat(series("cliente", RecurringSeries.Flow.INCOME).getOccurrences()).isEqualTo(3);
        assertThat(find("cliente", RecurringSeries.Flow.INTERNAL)).isEmpty();
    }

    @Test
    void cardBillPaymentLegsFuseIntoOneInternalSeries() {
        // As DUAS pernas do pagamento de fatura, marcadas na importação
        // (EC-106): a saída da conta corrente e o crédito que entra no cartão.
        // Antes, a âncora "fatura" impedia INTERNAL e nasciam duas séries
        // mensais — "fatura|EXPENSE" e "fatura|INCOME" — e a previsão de saldo
        // projetava uma receita do tamanho da fatura que não existe.
        for (int month = 4; month <= 6; month++) {
            transactions.add(txInternal("PAGAMENTO FATURA CARTAO", "DEBIT", "-500.00", day(2025, month, 12)));
            transactions.add(txInternal("Pagamento de fatura", "CREDIT", "500.00", day(2025, month, 12)));
        }

        service.detect(EMAIL);

        assertThat(find("fatura", RecurringSeries.Flow.EXPENSE)).isEmpty();
        assertThat(find("fatura", RecurringSeries.Flow.INCOME)).isEmpty();
        // os dois sentidos caem na MESMA série, pela fusão que já existia
        assertThat(series("fatura", RecurringSeries.Flow.INTERNAL).getOccurrences()).isEqualTo(6);
    }

    @Test
    void unmarkedFaturaStillBecomesExpenseSeries() {
        // sem a marca da importação (upload manual de OFX, usuário sem cartão
        // conectado) nada muda: o pagamento da fatura segue sendo a única
        // representação do gasto do cartão e continua despesa
        transactions.add(tx("Pagamento fatura cartao", "DEBIT", "-500.00", day(2025, 4, 12)));
        transactions.add(tx("Pagamento fatura cartao", "DEBIT", "-480.00", day(2025, 5, 12)));
        transactions.add(tx("Pagamento fatura cartao", "DEBIT", "-520.00", day(2025, 6, 12)));

        service.detect(EMAIL);

        assertThat(series("fatura", RecurringSeries.Flow.EXPENSE).getOccurrences()).isEqualTo(3);
        assertThat(find("fatura", RecurringSeries.Flow.INTERNAL)).isEmpty();
    }

    @Test
    void faturamentoDoesNotFallIntoFaturaAnchor() {
        // "faturamento" contém "fatura" como substring, mas é outra entidade:
        // a âncora agora casa por token exato
        transactions.add(tx("Recebimento faturamento mensal", "CREDIT", "1200.00", day(2025, 4, 12)));
        transactions.add(tx("Recebimento faturamento mensal", "CREDIT", "1200.00", day(2025, 5, 12)));
        transactions.add(tx("Recebimento faturamento mensal", "CREDIT", "1200.00", day(2025, 6, 12)));

        service.detect(EMAIL);

        assertThat(find("fatura", RecurringSeries.Flow.INCOME)).isEmpty();
        RecurringSeries billing = series("faturamento", RecurringSeries.Flow.INCOME);
        assertThat(billing.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(billing.getOccurrences()).isEqualTo(3);
    }

    @Test
    void fixedDetectionUsesTrueMedianOfAmountsNotChronologicalMiddle() {
        // meio CRONOLÓGICO = 1000 → banda de 50 engoliria a variação de 48 e
        // classificaria FIXED; a mediana real (955) dá banda de 47.75 e expõe
        transactions.add(tx("Plano Saude Vitalis", "DEBIT", "-952.00", day(2025, 4, 20)));
        transactions.add(tx("Plano Saude Vitalis", "DEBIT", "-1000.00", day(2025, 5, 20)));
        transactions.add(tx("Plano Saude Vitalis", "DEBIT", "-955.00", day(2025, 6, 20)));

        service.detect(EMAIL);

        RecurringSeries plan = series("vitalis", RecurringSeries.Flow.EXPENSE);
        assertThat(plan.getAmountType()).isEqualTo(RecurringSeries.AmountType.VARIABLE);
        assertThat(plan.getExpectedAmount()).isEqualByComparingTo("969.0000");
    }

    @Test
    void monthlyCadenceSurvivesMissedMonths() {
        // um mês falhado: gaps [31, 59] — a mediana superior dava IRREGULAR
        transactions.add(tx("Academia Corpo Livre", "DEBIT", "-99.90", day(2025, 1, 10)));
        transactions.add(tx("Academia Corpo Livre", "DEBIT", "-99.90", day(2025, 2, 10)));
        transactions.add(tx("Academia Corpo Livre", "DEBIT", "-99.90", day(2025, 4, 10)));
        // dois meses falhados: gaps [30, 90] — a mediana superior dava QUARTERLY
        // falso; no empate MONTHLY×QUARTERLY vence o ciclo mais curto
        transactions.add(tx("Clube Recreio Bom", "DEBIT", "-80.00", day(2025, 1, 10)));
        transactions.add(tx("Clube Recreio Bom", "DEBIT", "-80.00", day(2025, 2, 9)));
        transactions.add(tx("Clube Recreio Bom", "DEBIT", "-80.00", day(2025, 5, 10)));

        service.detect(EMAIL);

        RecurringSeries gym = series("academia", RecurringSeries.Flow.EXPENSE);
        assertThat(gym.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(gym.getAnchorDay()).isEqualTo((short) 10);

        RecurringSeries club = series("recreio", RecurringSeries.Flow.EXPENSE);
        assertThat(club.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
    }

    @Test
    void dismissedSeriesNeverResurrectsOnNewEvidence() {
        List<BankTransaction> gym = List.of(
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 1, 10)),
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 2, 10)),
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 3, 10)));
        transactions.addAll(gym);
        // cobrança NOVA chega DEPOIS de o usuário ter descartado a série
        transactions.add(tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 4, 10)));

        RecurringSeries dismissed = fitmaxSeries(gym);
        dismissed.setActive(false);
        dismissed.setDismissed(true);
        seriesStore.add(dismissed);
        linkGymTransactions(dismissed, gym);

        service.detect(EMAIL);

        // a evidência é registrada (vínculo + contagem), mas o descarte fica de pé
        assertThat(dismissed.isActive()).isFalse();
        assertThat(dismissed.isDismissed()).isTrue();
        assertThat(dismissed.getOccurrences()).isEqualTo(4);
        assertThat(linkStore).hasSize(4);
    }

    @Test
    void machineDeactivatedSeriesStillReactivatesOnNewEvidence() {
        List<BankTransaction> gym = List.of(
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 1, 10)),
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 2, 10)),
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 3, 10)));
        transactions.addAll(gym);
        transactions.add(tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 4, 10)));

        // desativada por staleness (dismissed=false): cobrança nova reativa
        RecurringSeries paused = fitmaxSeries(gym);
        paused.setActive(false);
        seriesStore.add(paused);
        linkGymTransactions(paused, gym);

        service.detect(EMAIL);

        assertThat(paused.isActive()).isTrue();
        assertThat(paused.isDismissed()).isFalse();
    }

    @Test
    void transactionAliasNeverFeedsTheEntityKeyNorTheSeriesName() {
        // as três cobranças foram renomeadas pelo usuário (EC-094); a série tem
        // que continuar nascendo do descritivo do banco, senão o apelido de uma
        // transação fatiaria a série ou criaria outra entidade do nada
        List<BankTransaction> streaming = List.of(
                tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-21.90", day(2025, 1, 31)),
                tx("Ebn*melodia Curitiba Bra", "DEBIT", "-21.90", day(2025, 3, 2)),
                tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-21.90", day(2025, 4, 1)));
        streaming.forEach(transaction -> transaction.setDisplayAlias("Streaming da Ana"));
        transactions.addAll(streaming);

        service.detect(EMAIL);

        assertThat(seriesStore).hasSize(1);
        RecurringSeries series = series("melodia", RecurringSeries.Flow.EXPENSE);
        assertThat(series.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(series.getOccurrences()).isEqualTo(3);
        // nome da série sai da última descrição do banco, não do apelido
        assertThat(series.getDisplayName()).isEqualTo("Dm*melodia Sao Paulo Bra");
        assertThat(seriesStore).noneMatch(s -> s.getMerchantKey().contains("streaming")
                || s.getMerchantKey().contains("ana"));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private void loadCoreFixtures() {
        // conta de água fictícia: rótulo muda no meio do histórico, valor varia
        transactions.add(txCat("Pagamento AQUANORTE | AQUANORTE", "DEBIT", "-85.30", day(2025, 1, 10)));
        transactions.add(txCat("Pagamento AQUANORTE | AQUANORTE", "DEBIT", "-92.10", day(2025, 2, 10)));
        transactions.add(txCat("Pagamento AQUANORTE | AQUANORTE", "DEBIT", "-78.55", day(2025, 3, 11)));
        transactions.add(txCat("Pagamento de Convênio | AQUANORTE", "DEBIT", "-101.20", day(2025, 4, 10)));
        transactions.add(txCat("Pagamento de Convênio | AQUANORTE", "DEBIT", "-88.00", day(2025, 5, 9)));
        transactions.add(txCat("Pagamento de Convênio | AQUANORTE", "DEBIT", "-90.45", day(2025, 6, 10)));

        // streaming fictício: adquirente e cidade mudam, cobrança vira o mês (30-03)
        transactions.add(tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-21.90", day(2025, 1, 31)));
        transactions.add(tx("Ebn*melodia Curitiba Bra", "DEBIT", "-21.90", day(2025, 3, 2)));
        transactions.add(tx("No Estabelecimento Dm *melodia Stockholm Bra", "DEBIT", "-21.90", day(2025, 4, 1)));
        transactions.add(tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-21.90", day(2025, 5, 3)));

        // fatura de cartão fictícia: dois rótulos sem nada em comum além da palavra
        transactions.add(tx("Pagamento efetuado | Fatura cartão Aurora", "DEBIT", "-1450.10", day(2025, 3, 5)));
        transactions.add(tx("Pagamento efetuado | Pagamento Fatura - CARLOS PEREIRA", "DEBIT", "-1320.55", day(2025, 4, 4)));
        transactions.add(tx("Pagamento efetuado | Fatura cartão Aurora", "DEBIT", "-1510.00", day(2025, 5, 6)));
        transactions.add(tx("Pagamento efetuado | Pagamento Fatura - CARLOS PEREIRA", "DEBIT", "-1275.40", day(2025, 6, 5)));

        // plano de celular fictício: upgrade de plano e mudança para trimestral
        transactions.add(tx("Zetacel Pre 10gb Mensal", "DEBIT", "-19.99", day(2025, 1, 8)));
        transactions.add(tx("Zetacel Pre 10gb Mensal", "DEBIT", "-19.99", day(2025, 2, 7)));
        transactions.add(tx("Zetacel Pre 15gb Mensal", "DEBIT", "-24.99", day(2025, 3, 8)));
        transactions.add(tx("Zetacel Pre 15gb Mensal", "DEBIT", "-24.99", day(2025, 4, 8)));
        transactions.add(tx("Zetacel Cel Trimestral 20GB", "DEBIT", "-69.90", day(2025, 6, 9)));

        // titular movendo dinheiro entre os próprios bancos (par PIX no mesmo dia)
        transactions.add(tx("Pix enviado para Carlos Pereira", "DEBIT", "-500.00", day(2025, 4, 1)));
        transactions.add(tx("Pix recebido de Carlos Pereira", "CREDIT", "500.00", day(2025, 4, 1)));
        transactions.add(tx("Pix enviado para Carlos Pereira", "DEBIT", "-650.00", day(2025, 5, 1)));
        transactions.add(tx("Pix recebido de Carlos Pereira", "CREDIT", "650.00", day(2025, 5, 1)));
        transactions.add(tx("Pix enviado para Carlos Pereira", "DEBIT", "-600.00", day(2025, 6, 1)));
        transactions.add(tx("Pix recebido de Carlos Pereira", "CREDIT", "600.00", day(2025, 6, 1)));

        // salário via portabilidade (rótulo genérico, sem empregador)
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 4, 30)));
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 5, 30)));
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 6, 30)));

        // renda informal: PIX do mesmo nome fictício todo mês
        transactions.add(tx("Pix recebido de Joana Prado", "CREDIT", "800.00", day(2025, 4, 15)));
        transactions.add(tx("Pix recebido de Joana Prado", "CREDIT", "750.00", day(2025, 5, 15)));
        transactions.add(tx("Pix recebido de Joana Prado", "CREDIT", "820.00", day(2025, 6, 16)));

        // ruído: compras esparsas que NÃO podem virar série
        transactions.add(tx("Ifd*Cantina Da Nona Sao Paulo Bra", "DEBIT", "-54.30", day(2025, 5, 20)));
        transactions.add(tx("Padaria Estrela do Sul", "DEBIT", "-12.50", day(2025, 5, 21)));
        transactions.add(tx("Padaria Estrela do Sul", "DEBIT", "-15.00", day(2025, 6, 21)));
    }

    /** Gasto fixo agendado pelo usuário (EC-096), como o POST o teria criado. */
    private RecurringSeries scheduledUtilities(String merchantKey, RecurringSeries.AmountType amountType,
                                               String expectedAmount) {
        return RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey(merchantKey).flow(RecurringSeries.Flow.EXPENSE)
                .displayName("Conta de Luz")
                .cadence(RecurringSeries.Cadence.MONTHLY).anchorDay((short) 12)
                .amountType(amountType)
                .expectedAmount(new BigDecimal(expectedAmount))
                .occurrences(0)
                .active(true).source(RecurringSeries.Source.USER)
                .startsAt(LocalDate.of(2025, 6, 1))
                .build();
    }

    /** Série da academia fictícia como uma varredura anterior a teria gravado. */
    private RecurringSeries fitmaxSeries(List<BankTransaction> gym) {
        return RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey("fitmax").flow(RecurringSeries.Flow.EXPENSE)
                .displayName("Dm*fitmax Sao Paulo Bra")
                .cadence(RecurringSeries.Cadence.MONTHLY)
                .anchorDay((short) 10).dayTolerance((short) 0)
                .amountType(RecurringSeries.AmountType.FIXED)
                .expectedAmount(new BigDecimal("99.9000"))
                .occurrences(gym.size())
                .firstSeenAt(gym.get(0).getDate()).lastSeenAt(gym.get(gym.size() - 1).getDate())
                .active(true).source(RecurringSeries.Source.DETECTED)
                .build();
    }

    private void linkGymTransactions(RecurringSeries series, List<BankTransaction> gym) {
        for (BankTransaction tx : gym) {
            linkStore.add(RecurringSeriesLink.builder()
                    .id(UUID.randomUUID()).seriesId(series.getId())
                    .bankTransactionId(tx.getId()).matchedAt(OffsetDateTime.now()).build());
        }
    }

    private RecurringSeries series(String merchantKey, RecurringSeries.Flow flow) {
        return find(merchantKey, flow).orElseThrow(
                () -> new AssertionError("Série não encontrada: " + merchantKey + "/" + flow));
    }

    private Optional<RecurringSeries> find(String merchantKey, RecurringSeries.Flow flow) {
        return seriesStore.stream()
                .filter(s -> s.getMerchantKey().equals(merchantKey) && s.getFlow() == flow)
                .findFirst();
    }

    private BankTransaction tx(String description, String type, String amount, OffsetDateTime date) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .transactionId(UUID.randomUUID().toString())
                .type(type)
                .amount(new BigDecimal(amount))
                .description(description)
                .date(date)
                .build();
    }

    /** Perna de movimentação entre contas do titular, marcada na importação. */
    private BankTransaction txInternal(String description, String type, String amount, OffsetDateTime date) {
        BankTransaction transaction = tx(description, type, amount, date);
        transaction.setInternalTransfer(true);
        return transaction;
    }

    private BankTransaction txCat(String description, String type, String amount, OffsetDateTime date) {
        BankTransaction transaction = tx(description, type, amount, date);
        transaction.setCategoryId(utilitiesCategory);
        return transaction;
    }

    private OffsetDateTime day(int year, int month, int dayOfMonth) {
        return OffsetDateTime.of(LocalDate.of(year, month, dayOfMonth), LocalTime.NOON, ZoneOffset.UTC);
    }
}
