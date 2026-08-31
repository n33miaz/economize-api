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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringSeriesServiceTest {

    private static final String EMAIL = "ana@economize.dev";

    @Mock
    private RecurringSeriesRepository seriesRepository;

    @Mock
    private RecurringSeriesLinkRepository linkRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private RecurringSeriesService service;

    private final User user = User.builder()
            .id(UUID.randomUUID()).name("Ana").email(EMAIL).password("x").build();

    @Test
    void listHidesInternalAndInactiveByDefaultAndSortsByNextDue() {
        RecurringSeries expense = series("melodia", RecurringSeries.Flow.EXPENSE, true);
        expense.setCadence(RecurringSeries.Cadence.MONTHLY);
        expense.setAnchorDay((short) 1);
        expense.setLastSeenAt(day(2025, 1, 31));
        RecurringSeries income = series("salario", RecurringSeries.Flow.INCOME, true);
        income.setCadence(RecurringSeries.Cadence.WEEKLY);
        income.setLastSeenAt(day(2025, 6, 1));
        RecurringSeries internal = series("pereira", RecurringSeries.Flow.INTERNAL, true);
        RecurringSeries inactive = series("fitmax", RecurringSeries.Flow.EXPENSE, false);

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findAllByUserId(user.getId()))
                .thenReturn(List.of(income, expense, internal, inactive));

        List<RecurringSeriesResponse> result = service.list(EMAIL, null, null);

        assertThat(result).extracting(RecurringSeriesResponse::merchantKey)
                .containsExactly("melodia", "salario"); // ordenado pelo próximo vencimento
        // dia 31 + 1 mês ajustado à âncora 1 = dia 1 do mês SEGUINTE à virada
        assertThat(result.get(0).nextDueDate()).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(result.get(1).nextDueDate()).isEqualTo(LocalDate.of(2025, 6, 8));
    }

    @Test
    void listShowsInternalOnlyWhenExplicitlyRequested() {
        RecurringSeries internal = series("pereira", RecurringSeries.Flow.INTERNAL, true);
        internal.setCadence(RecurringSeries.Cadence.MONTHLY);
        internal.setAnchorDay((short) 1);
        internal.setLastSeenAt(day(2025, 6, 1));
        RecurringSeries expense = series("melodia", RecurringSeries.Flow.EXPENSE, true);

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findAllByUserId(user.getId())).thenReturn(List.of(internal, expense));

        List<RecurringSeriesResponse> result = service.list(EMAIL, "INTERNAL", null);

        assertThat(result).extracting(RecurringSeriesResponse::merchantKey).containsExactly("pereira");
    }

    @Test
    void listRejectsUnknownFlow() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.list(EMAIL, "WEEKLY", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Fluxo inválido");
    }

    @Test
    void listWithActiveFalseExposesInactiveIncludingDismissed() {
        RecurringSeries activeSeries = series("melodia", RecurringSeries.Flow.EXPENSE, true);
        RecurringSeries stale = series("fitmax", RecurringSeries.Flow.EXPENSE, false);
        RecurringSeries dismissed = series("fatura", RecurringSeries.Flow.EXPENSE, false);
        dismissed.setDismissed(true);

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findAllByUserId(user.getId()))
                .thenReturn(List.of(activeSeries, stale, dismissed));

        // sem descarte visível o usuário não teria como reativar via PATCH
        List<RecurringSeriesResponse> inactive = service.list(EMAIL, null, false);
        assertThat(inactive).extracting(RecurringSeriesResponse::merchantKey)
                .containsExactlyInAnyOrder("fitmax", "fatura");
        assertThat(inactive).filteredOn(RecurringSeriesResponse::dismissed)
                .extracting(RecurringSeriesResponse::merchantKey).containsExactly("fatura");

        List<RecurringSeriesResponse> defaultList = service.list(EMAIL, null, null);
        assertThat(defaultList).extracting(RecurringSeriesResponse::merchantKey)
                .containsExactly("melodia");
    }

    @Test
    void nextDueDateSnapsToAnchorAcrossMonthBoundary() {
        RecurringSeries series = series("fatura", RecurringSeries.Flow.EXPENSE, true);
        series.setCadence(RecurringSeries.Cadence.MONTHLY);
        series.setAnchorDay((short) 4);
        series.setLastSeenAt(day(2025, 2, 5));

        assertThat(RecurringSeriesService.nextDueDate(series)).isEqualTo(LocalDate.of(2025, 3, 4));
    }

    @Test
    void nextDueDateHandlesQuarterlyAndIrregular() {
        RecurringSeries quarterly = series("zetacel", RecurringSeries.Flow.EXPENSE, true);
        quarterly.setCadence(RecurringSeries.Cadence.QUARTERLY);
        quarterly.setAnchorDay((short) 9);
        quarterly.setLastSeenAt(day(2025, 6, 9));
        assertThat(RecurringSeriesService.nextDueDate(quarterly)).isEqualTo(LocalDate.of(2025, 9, 9));

        RecurringSeries irregular = series("cantina", RecurringSeries.Flow.EXPENSE, true);
        irregular.setCadence(RecurringSeries.Cadence.IRREGULAR);
        irregular.setLastSeenAt(day(2025, 6, 9));
        assertThat(RecurringSeriesService.nextDueDate(irregular)).isNull();
    }

    @Test
    void updateValidatesOwnershipViaFindByIdAndUserId() {
        UUID id = UUID.randomUUID();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(id, user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(EMAIL, id,
                new UpdateRecurringSeriesRequest("Nome", null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não encontrada");
    }

    @Test
    void updateAppliesOnlyProvidedFields() {
        RecurringSeries series = series("melodia", RecurringSeries.Flow.EXPENSE, true);
        series.setCadence(RecurringSeries.Cadence.MONTHLY);
        series.setLastSeenAt(day(2025, 6, 1));
        Category category = Category.builder()
                .id(UUID.randomUUID()).name("Assinaturas").slug("assinaturas")
                .flow(Category.Flow.EXPENSE).archived(false).build();

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(series.getId(), user.getId())).thenReturn(Optional.of(series));
        when(categoryRepository.findAccessible(category.getId(), user.getId())).thenReturn(Optional.of(category));

        RecurringSeriesResponse response = service.update(EMAIL, series.getId(),
                new UpdateRecurringSeriesRequest("Streaming Melodia", category.getId(), false,
                        "VARIABLE", new BigDecimal("25.90"), null, null, null, null));

        assertThat(series.getDisplayName()).isEqualTo("Streaming Melodia");
        assertThat(series.getCategoryId()).isEqualTo(category.getId());
        assertThat(series.isActive()).isFalse();
        assertThat(series.getAmountType()).isEqualTo(RecurringSeries.AmountType.VARIABLE);
        assertThat(series.getExpectedAmount()).isEqualByComparingTo("25.90");
        // valor/tipo editados à mão promovem a USER: curadoria que a varredura
        // não pode mais recalcular
        assertThat(series.getSource()).isEqualTo(RecurringSeries.Source.USER);
        assertThat(response.displayName()).isEqualTo("Streaming Melodia");
        verify(seriesRepository).save(series);
    }

    @Test
    void updateOfNameOrCategoryAloneDoesNotPromoteToUserSource() {
        RecurringSeries series = series("melodia", RecurringSeries.Flow.EXPENSE, true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(series.getId(), user.getId())).thenReturn(Optional.of(series));

        service.update(EMAIL, series.getId(),
                new UpdateRecurringSeriesRequest("Apelido novo", null, null, null, null,
                        null, null, null, null));

        // nome/categoria já são protegidos um a um na varredura — promover aqui
        // congelaria cadência e âncora sem necessidade
        assertThat(series.getSource()).isEqualTo(RecurringSeries.Source.DETECTED);
    }

    @Test
    void reactivatingViaPatchClearsDismissal() {
        RecurringSeries series = series("fatura", RecurringSeries.Flow.EXPENSE, false);
        series.setDismissed(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(series.getId(), user.getId())).thenReturn(Optional.of(series));

        service.update(EMAIL, series.getId(),
                new UpdateRecurringSeriesRequest(null, null, true, null, null,
                        null, null, null, null));

        assertThat(series.isActive()).isTrue();
        assertThat(series.isDismissed()).isFalse();
    }

    @Test
    void updateRejectsCategoryNotAccessibleToUser() {
        RecurringSeries series = series("melodia", RecurringSeries.Flow.EXPENSE, true);
        UUID foreignCategory = UUID.randomUUID();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(series.getId(), user.getId())).thenReturn(Optional.of(series));
        when(categoryRepository.findAccessible(foreignCategory, user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(EMAIL, series.getId(),
                new UpdateRecurringSeriesRequest(null, foreignCategory, null, null, null,
                        null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Categoria não encontrada");
    }

    @Test
    void updateRejectsInvalidAmountType() {
        RecurringSeries series = series("melodia", RecurringSeries.Flow.EXPENSE, true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(series.getId(), user.getId())).thenReturn(Optional.of(series));

        assertThatThrownBy(() -> service.update(EMAIL, series.getId(),
                new UpdateRecurringSeriesRequest(null, null, null, "SOMETIMES", null,
                        null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tipo de valor inválido");
    }

    @Test
    void updateEditsValidityAndRhythmOfScheduledSeries() {
        RecurringSeries series = scheduled("aluguel", (short) 5,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(series.getId(), user.getId())).thenReturn(Optional.of(series));

        RecurringSeriesResponse response = service.update(EMAIL, series.getId(),
                new UpdateRecurringSeriesRequest(null, null, null, null, null,
                        "quarterly", 20, LocalDate.of(2026, 9, 1), LocalDate.of(2027, 6, 30)));

        assertThat(series.getCadence()).isEqualTo(RecurringSeries.Cadence.QUARTERLY);
        assertThat(series.getAnchorDay()).isEqualTo((short) 20);
        assertThat(series.getStartsAt()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(series.getEndsAt()).isEqualTo(LocalDate.of(2027, 6, 30));
        // é isso que o 409 promete quando manda "edite-a": a vigência corrigida
        // reposiciona o próximo vencimento na hora
        assertThat(response.nextDueDate()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    void updateValidatesTheResultingValidityWindowNotOnlyThePayload() {
        RecurringSeries series = scheduled("aluguel", (short) 5, LocalDate.of(2026, 8, 1), null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(series.getId(), user.getId())).thenReturn(Optional.of(series));

        // endsAt sozinho, anterior ao startsAt JÁ gravado: a série ficaria com
        // vigência impossível e sem nenhum vencimento a projetar
        assertThatThrownBy(() -> service.update(EMAIL, series.getId(),
                new UpdateRecurringSeriesRequest(null, null, null, null, null,
                        null, null, null, LocalDate.of(2026, 7, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endsAt não pode ser anterior");
        assertThat(series.getEndsAt()).isNull();
    }

    @Test
    void updateToWeeklyDropsTheAnchorDayAndRefusesToKeepOne() {
        RecurringSeries series = scheduled("feira", (short) 5, LocalDate.of(2026, 8, 1), null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(series.getId(), user.getId())).thenReturn(Optional.of(series));

        assertThatThrownBy(() -> service.update(EMAIL, series.getId(),
                new UpdateRecurringSeriesRequest(null, null, null, null, null,
                        "WEEKLY", 5, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não se aplica");

        service.update(EMAIL, series.getId(), new UpdateRecurringSeriesRequest(
                null, null, null, null, null, "WEEKLY", null, null, null));

        assertThat(series.getCadence()).isEqualTo(RecurringSeries.Cadence.WEEKLY);
        assertThat(series.getAnchorDay()).isNull();
    }

    @Test
    void updateRefusesToLeaveACyclicalSeriesWithoutAnchorDay() {
        RecurringSeries series = scheduled("feira", null, LocalDate.of(2026, 8, 1), null);
        series.setCadence(RecurringSeries.Cadence.WEEKLY);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(series.getId(), user.getId())).thenReturn(Optional.of(series));

        assertThatThrownBy(() -> service.update(EMAIL, series.getId(),
                new UpdateRecurringSeriesRequest(null, null, null, null, null,
                        "MONTHLY", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dia âncora é obrigatório");
    }

    @Test
    void updateOfRhythmPromotesDetectedSeriesToUserSource() {
        RecurringSeries series = series("melodia", RecurringSeries.Flow.EXPENSE, true);
        series.setCadence(RecurringSeries.Cadence.MONTHLY);
        series.setAnchorDay((short) 10);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(series.getId(), user.getId())).thenReturn(Optional.of(series));

        service.update(EMAIL, series.getId(), new UpdateRecurringSeriesRequest(
                null, null, null, null, null, null, 15, null, null));

        assertThat(series.getAnchorDay()).isEqualTo((short) 15);
        // cadência e âncora são recalculadas pela varredura em série DETECTED:
        // sem promover, o PATCH seria desfeito na importação seguinte
        assertThat(series.getSource()).isEqualTo(RecurringSeries.Source.USER);
    }

    @Test
    void updateOfValidityAloneDoesNotPromoteToUserSource() {
        RecurringSeries series = series("melodia", RecurringSeries.Flow.EXPENSE, true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(series.getId(), user.getId())).thenReturn(Optional.of(series));

        service.update(EMAIL, series.getId(), new UpdateRecurringSeriesRequest(
                null, null, null, null, null, null, null, null, LocalDate.of(2026, 12, 31)));

        // vigência não é campo estatístico: a varredura nunca a escreve, então
        // não há curadoria a congelar
        assertThat(series.getEndsAt()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(series.getSource()).isEqualTo(RecurringSeries.Source.DETECTED);
    }

    @Test
    void createWithoutHintDerivesKeyFromDisplayNameAndAppliesDefaults() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByUserIdAndMerchantKeyAndFlow(
                user.getId(), "aluguel", RecurringSeries.Flow.EXPENSE)).thenReturn(Optional.empty());

        RecurringSeriesResponse response = service.create(EMAIL, new CreateRecurringSeriesRequest(
                "Aluguel", "expense", "monthly", 5, new BigDecimal("1500.00"),
                null, null, null, null, null));

        assertThat(response.merchantKey()).isEqualTo("aluguel");
        assertThat(response.source()).isEqualTo(RecurringSeries.Source.USER);
        assertThat(response.flow()).isEqualTo(RecurringSeries.Flow.EXPENSE);
        assertThat(response.cadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(response.anchorDay()).isEqualTo(5);
        // defaults: FIXED, começa hoje, sem fim, ativa, zero ocorrências
        assertThat(response.amountType()).isEqualTo(RecurringSeries.AmountType.FIXED);
        assertThat(response.startsAt()).isEqualTo(LocalDate.now());
        assertThat(response.endsAt()).isNull();
        assertThat(response.active()).isTrue();
        assertThat(response.occurrences()).isZero();
        verify(seriesRepository).saveAndFlush(org.mockito.ArgumentMatchers.any(RecurringSeries.class));
    }

    @Test
    void createWithMatchHintDerivesKeyThroughTheSameExtractorAsDetection() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByUserIdAndMerchantKeyAndFlow(
                user.getId(), "luzdomani", RecurringSeries.Flow.EXPENSE)).thenReturn(Optional.empty());

        // o hint passa pelo pipeline completo: prefixo de adquirente e sufixo
        // cidade+"Bra" caem, exatamente como cairão nas transações reais
        RecurringSeriesResponse response = service.create(EMAIL, new CreateRecurringSeriesRequest(
                "Energia", "EXPENSE", "MONTHLY", 12, new BigDecimal("230.00"),
                "VARIABLE", null, "Dm*luzdomani Sao Paulo Bra", LocalDate.of(2026, 8, 1), null));

        assertThat(response.merchantKey()).isEqualTo("luzdomani");
        assertThat(response.displayName()).isEqualTo("Energia");
        assertThat(response.amountType()).isEqualTo(RecurringSeries.AmountType.VARIABLE);
        // agendada sem histórico: o próximo vencimento sai da vigência + âncora
        assertThat(response.nextDueDate()).isEqualTo(LocalDate.of(2026, 8, 12));
    }

    @Test
    void createScheduledDueSkipsToNextMonthWhenAnchorPrecedesStart() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByUserIdAndMerchantKeyAndFlow(
                user.getId(), "aluguel", RecurringSeries.Flow.EXPENSE)).thenReturn(Optional.empty());

        RecurringSeriesResponse response = service.create(EMAIL, new CreateRecurringSeriesRequest(
                "Aluguel", "EXPENSE", "MONTHLY", 5, new BigDecimal("1500.00"),
                null, null, null, LocalDate.of(2026, 8, 15), null));

        // dia 5 já passou dentro do mês de início: a 1ª cobrança é no mês seguinte
        assertThat(response.nextDueDate()).isEqualTo(LocalDate.of(2026, 9, 5));
    }

    @Test
    void createCollisionAnswersConflictNamingTheExistingSeries() {
        RecurringSeries existing = series("aluguel", RecurringSeries.Flow.EXPENSE, true);
        existing.setDisplayName("Aluguel Centro");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByUserIdAndMerchantKeyAndFlow(
                user.getId(), "aluguel", RecurringSeries.Flow.EXPENSE)).thenReturn(Optional.of(existing));

        Throwable conflict = catchThrowable(() -> service.create(EMAIL, new CreateRecurringSeriesRequest(
                "Aluguel", "EXPENSE", "MONTHLY", 5, new BigDecimal("1500.00"),
                null, null, null, null, null)));

        assertThat(conflict).isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("Aluguel Centro");
        // o id vai junto no ProblemDetail: é com ele que a UI oferece
        // "editar/reativar" sem obrigar o usuário a caçar a série na lista
        assertThat(((ResourceConflictException) conflict).getProperties())
                .containsEntry("seriesId", existing.getId());
        verify(seriesRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any(RecurringSeries.class));
    }

    @Test
    void createTranslatesTheUniqueRaceWithTheScannerIntoConflict() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        // a consulta prévia não vê nada: a varredura pós-importação só grava a
        // mesma chave DEPOIS dela, e quem recusa é o unique do banco
        when(seriesRepository.findByUserIdAndMerchantKeyAndFlow(
                user.getId(), "aluguel", RecurringSeries.Flow.EXPENSE)).thenReturn(Optional.empty());
        when(seriesRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(RecurringSeries.class)))
                .thenThrow(new DataIntegrityViolationException("uq_recurring_series_user_key_flow"));

        assertThatThrownBy(() -> service.create(EMAIL, new CreateRecurringSeriesRequest(
                "Aluguel", "EXPENSE", "MONTHLY", 5, new BigDecimal("1500.00"),
                null, null, null, null, null)))
                // 409 e não 500: o payload está certo, o estado é que mudou
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("aluguel");
    }

    @Test
    void createRejectsInternalFlowAndIrregularCadence() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.create(EMAIL, new CreateRecurringSeriesRequest(
                "Movimentação", "INTERNAL", "MONTHLY", 5, new BigDecimal("100.00"),
                null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXPENSE ou INCOME");

        assertThatThrownBy(() -> service.create(EMAIL, new CreateRecurringSeriesRequest(
                "Extra", "EXPENSE", "IRREGULAR", 5, new BigDecimal("100.00"),
                null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MONTHLY, WEEKLY ou QUARTERLY");
    }

    @Test
    void createValidatesAnchorDayPerCadence() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        // MONTHLY/QUARTERLY sem âncora não têm data de projeção
        assertThatThrownBy(() -> service.create(EMAIL, new CreateRecurringSeriesRequest(
                "Aluguel", "EXPENSE", "MONTHLY", null, new BigDecimal("1500.00"),
                null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dia âncora é obrigatório");

        // WEEKLY não tem dia do mês
        assertThatThrownBy(() -> service.create(EMAIL, new CreateRecurringSeriesRequest(
                "Feira", "EXPENSE", "WEEKLY", 5, new BigDecimal("120.00"),
                null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não se aplica");
    }

    @Test
    void createRejectsEndBeforeStartAndForeignCategory() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.create(EMAIL, new CreateRecurringSeriesRequest(
                "Aluguel", "EXPENSE", "MONTHLY", 5, new BigDecimal("1500.00"),
                null, null, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endsAt não pode ser anterior");

        UUID foreignCategory = UUID.randomUUID();
        when(categoryRepository.findAccessible(foreignCategory, user.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(EMAIL, new CreateRecurringSeriesRequest(
                "Aluguel", "EXPENSE", "MONTHLY", 5, new BigDecimal("1500.00"),
                null, foreignCategory, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Categoria não encontrada");
    }

    @Test
    void deleteRemovesUserSeriesWithoutLinksForReal() {
        RecurringSeries series = series("boletos", RecurringSeries.Flow.EXPENSE, true);
        series.setSource(RecurringSeries.Source.USER);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(series.getId(), user.getId())).thenReturn(Optional.of(series));
        when(linkRepository.existsBySeriesId(series.getId())).thenReturn(false);

        boolean deleted = service.delete(EMAIL, series.getId());

        assertThat(deleted).isTrue();
        verify(seriesRepository).delete(series);
    }

    @Test
    void deleteDismissesUserSeriesThatAlreadyMatchedTransactions() {
        // série promovida a USER (curadoria) com vínculos: o histórico alimenta o
        // EC-096 — descarta em vez de apagar
        RecurringSeries series = series("melodia", RecurringSeries.Flow.EXPENSE, true);
        series.setSource(RecurringSeries.Source.USER);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(series.getId(), user.getId())).thenReturn(Optional.of(series));
        when(linkRepository.existsBySeriesId(series.getId())).thenReturn(true);

        boolean deleted = service.delete(EMAIL, series.getId());

        assertThat(deleted).isFalse();
        assertThat(series.isActive()).isFalse();
        assertThat(series.isDismissed()).isTrue();
        verify(seriesRepository).save(series);
        verify(seriesRepository, never()).delete(series);
    }

    @Test
    void deleteDismissesDetectedSeries() {
        RecurringSeries series = series("melodia", RecurringSeries.Flow.EXPENSE, true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(seriesRepository.findByIdAndUserId(series.getId(), user.getId())).thenReturn(Optional.of(series));

        boolean deleted = service.delete(EMAIL, series.getId());

        // dismissed impede a ressurreição quando chega cobrança nova
        assertThat(deleted).isFalse();
        assertThat(series.isActive()).isFalse();
        assertThat(series.isDismissed()).isTrue();
        verify(seriesRepository).save(series);
        verify(seriesRepository, never()).delete(series);
    }

    /** Série agendada pelo usuário (source=USER), como o POST a teria criado. */
    private RecurringSeries scheduled(String merchantKey, Short anchorDay,
                                      LocalDate startsAt, LocalDate endsAt) {
        RecurringSeries series = series(merchantKey, RecurringSeries.Flow.EXPENSE, true);
        series.setSource(RecurringSeries.Source.USER);
        series.setCadence(RecurringSeries.Cadence.MONTHLY);
        series.setAnchorDay(anchorDay);
        series.setExpectedAmount(new BigDecimal("1500.00"));
        series.setOccurrences(0);
        series.setStartsAt(startsAt);
        series.setEndsAt(endsAt);
        return series;
    }


    // ------------------------------------------------- EC-136: o que já tem dono

    @Test
    void vencimentosNaJanelaSaemEmOrdemDeData() {
        when(seriesRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                mensal("aluguel", 10, "1800.00", day(2026, 8, 10)),
                mensal("streaming", 2, "39.90", day(2026, 8, 2))));

        List<RecurringSeriesService.UpcomingDue> dues = service.upcomingExpenses(
                user.getId(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertThat(dues).extracting(RecurringSeriesService.UpcomingDue::name)
                .containsExactly("streaming", "aluguel");
        assertThat(dues.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    void serieQueVenceForaDaJanelaNaoEntra() {
        when(seriesRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                mensal("aluguel", 10, "1800.00", day(2026, 8, 10))));

        assertThat(service.upcomingExpenses(user.getId(),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5))).isEmpty();
    }

    @Test
    void rendaEIrregularNaoContamComoContaAPagar() {
        RecurringSeries renda = mensal("salario", 5, "4400.00", day(2026, 8, 5));
        renda.setFlow(RecurringSeries.Flow.INCOME);
        RecurringSeries irregular = mensal("freela", 5, "500.00", day(2026, 8, 5));
        irregular.setCadence(RecurringSeries.Cadence.IRREGULAR);
        when(seriesRepository.findAllByUserId(user.getId()))
                .thenReturn(List.of(renda, irregular));

        // renda não é conta; sem cadência não há vencimento a prever, e chutar
        // um comprometeria o número que decide se a pessoa pode gastar
        assertThat(service.upcomingExpenses(user.getId(),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).isEmpty();
    }

    @Test
    void serieDescartadaOuInativaNaoEntra() {
        RecurringSeries descartada = mensal("antiga", 10, "100.00", day(2026, 8, 10));
        descartada.setDismissed(true);
        RecurringSeries inativa = mensal("parada", 10, "100.00", day(2026, 8, 10));
        inativa.setActive(false);
        when(seriesRepository.findAllByUserId(user.getId()))
                .thenReturn(List.of(descartada, inativa));

        assertThat(service.upcomingExpenses(user.getId(),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).isEmpty();
    }

    @Test
    void semValorEsperadoNaoEntraNoTotal() {
        RecurringSeries semValor = mensal("misterio", 10, null, day(2026, 8, 10));
        when(seriesRepository.findAllByUserId(user.getId())).thenReturn(List.of(semValor));

        // somar zero mentiria para menos; a série continua na listagem, só não
        // vira "já tem dono"
        assertThat(service.upcomingExpenses(user.getId(),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).isEmpty();
    }

    @Test
    void janelaLongaTrazAsRepeticoesDaMesmaSerie() {
        when(seriesRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                mensal("aluguel", 10, "1800.00", day(2026, 8, 10))));

        List<RecurringSeriesService.UpcomingDue> dues = service.upcomingExpenses(
                user.getId(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 30));

        assertThat(dues).hasSize(3);
        assertThat(dues).extracting(RecurringSeriesService.UpcomingDue::dueDate)
                .containsExactly(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 10),
                        LocalDate.of(2026, 11, 10));
    }

    @Test
    void serieAntigaEParadaNaoTravaOAvancoAteAJanela() {
        // último vencimento em 2019: a corrente precisa avançar até 2026 sem
        // laço infinito — é o que o teto de passos protege
        when(seriesRepository.findAllByUserId(user.getId())).thenReturn(List.of(
                mensal("fantasma", 10, "50.00", day(2019, 1, 10))));

        List<RecurringSeriesService.UpcomingDue> dues = service.upcomingExpenses(
                user.getId(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertThat(dues).hasSize(1);
        assertThat(dues.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 9, 10));
    }

    @Test
    void contaDeConsumoVemMarcadaComoEstimativa() {
        RecurringSeries luz = mensal("luz", 15, "210.00", day(2026, 8, 15));
        luz.setAmountType(RecurringSeries.AmountType.VARIABLE);
        when(seriesRepository.findAllByUserId(user.getId())).thenReturn(List.of(luz));

        assertThat(service.upcomingExpenses(user.getId(),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).get(0).estimated()).isTrue();
    }

    @Test
    void janelaInvertidaDevolveVazioEmVezDeExplodir() {
        assertThat(service.upcomingExpenses(user.getId(),
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1))).isEmpty();
        assertThat(service.upcomingExpenses(user.getId(), null, null)).isEmpty();
    }

    private RecurringSeries mensal(String key, int anchorDay, String amount, OffsetDateTime lastSeen) {
        return RecurringSeries.builder()
                .id(UUID.randomUUID())
                .user(user)
                .merchantKey(key)
                .displayName(key)
                .flow(RecurringSeries.Flow.EXPENSE)
                .cadence(RecurringSeries.Cadence.MONTHLY)
                .amountType(RecurringSeries.AmountType.FIXED)
                .anchorDay((short) anchorDay)
                .expectedAmount(amount != null ? new BigDecimal(amount) : null)
                .occurrences(3)
                .lastSeenAt(lastSeen)
                .active(true)
                .dismissed(false)
                .source(RecurringSeries.Source.DETECTED)
                .build();
    }

    private RecurringSeries series(String merchantKey, RecurringSeries.Flow flow, boolean active) {
        return RecurringSeries.builder()
                .id(UUID.randomUUID())
                .user(user)
                .merchantKey(merchantKey)
                .displayName(merchantKey)
                .flow(flow)
                .cadence(RecurringSeries.Cadence.IRREGULAR)
                .amountType(RecurringSeries.AmountType.FIXED)
                .occurrences(3)
                .active(active)
                .source(RecurringSeries.Source.DETECTED)
                .build();
    }

    private OffsetDateTime day(int year, int month, int dayOfMonth) {
        return OffsetDateTime.of(LocalDate.of(year, month, dayOfMonth), LocalTime.NOON, ZoneOffset.UTC);
    }
}
