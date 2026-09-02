package br.com.economize.service.family;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.family.FamilyAnalyticsResponse;
import br.com.economize.dto.family.FamilyTransactionResponse;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.FamilyGroup;
import br.com.economize.model.FamilyMember;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.FamilyMemberRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A montagem da visão da casa (EC-149) com o repositório dublado: quem entra
 * no combinado, como o NONE aparece, de onde vem o nome da categoria e quais
 * filtros descem para a consulta. A cláusula em si roda de verdade em
 * {@code FamilyRepositoryTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FamilyAnalyticsService (EC-149)")
class FamilyAnalyticsServiceTest {

    private static final String ANA = "ana@economize.dev";
    private static final AnalysisWindow JULHO = AnalysisWindow.ofMonth(YearMonth.of(2026, 7));

    @Mock
    private FamilyMemberRepository memberRepository;
    @Mock
    private BankTransactionRepository bankTransactionRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private FamilyAnalyticsService service;

    private User ana;
    private User bia;
    private User caio;
    private FamilyGroup casa;
    private FamilyMember anaMe;
    private FamilyMember biaTransactions;
    private FamilyMember caioNone;

    private final Category alimentacao = system("Alimentação");
    private final Category saude = system("Saúde");
    private final Category farmacia = childOf(saude, "Farmácia");
    private Category petsDaBia;

    // materializa a projeção que o Spring Data devolveria da query agregada
    private record Row(UUID rowCategoryId, String rowType, BigDecimal rowTotal, long rowTxCount)
            implements BankTransactionRepository.CategoryTotal {
        @Override
        public UUID getCategoryId() {
            return rowCategoryId;
        }

        @Override
        public String getType() {
            return rowType;
        }

        @Override
        public BigDecimal getTotal() {
            return rowTotal;
        }

        @Override
        public long getTxCount() {
            return rowTxCount;
        }
    }

    @BeforeEach
    void setUp() {
        ana = User.builder().id(UUID.randomUUID()).name("Ana").email(ANA).password("x").build();
        bia = User.builder().id(UUID.randomUUID()).name("Bia").email("bia@economize.dev").password("x").build();
        caio = User.builder().id(UUID.randomUUID()).name("Caio").email("caio@economize.dev").password("x").build();
        when(userRepository.findByEmail(ANA)).thenReturn(Optional.of(ana));

        casa = FamilyGroup.builder().id(UUID.randomUUID()).name("Casa").owner(ana).build();
        // Ana é quem pergunta e escolheu NONE: os outros não veem nada dela,
        // mas ela continua vendo a si mesma por inteiro
        anaMe = member(ana, FamilyMember.Role.OWNER, FamilyMember.ShareScope.NONE);
        biaTransactions = member(bia, FamilyMember.Role.MEMBER, FamilyMember.ShareScope.TRANSACTIONS);
        biaTransactions.getHiddenCategoryIds().add(saude.getId());
        caioNone = member(caio, FamilyMember.Role.MEMBER, FamilyMember.ShareScope.NONE);

        when(memberRepository.findByUserId(ana.getId())).thenReturn(Optional.of(anaMe));
        when(memberRepository.findAllByGroupIdOrderByJoinedAtAsc(casa.getId()))
                .thenReturn(List.of(anaMe, biaTransactions, caioNone));
        when(memberRepository.findByIdAndGroupId(biaTransactions.getId(), casa.getId()))
                .thenReturn(Optional.of(biaTransactions));

        petsDaBia = Category.builder().id(UUID.randomUUID()).name("Pets").slug("pets")
                .user(bia).flow(Category.Flow.EXPENSE).build();
        when(categoryRepository.findVisibleTo(ana.getId())).thenReturn(List.of(alimentacao, saude, farmacia));
        when(categoryRepository.findVisibleTo(bia.getId())).thenReturn(List.of(alimentacao, saude, farmacia, petsDaBia));
        when(categoryRepository.findVisibleTo(caio.getId())).thenReturn(List.of(alimentacao, saude, farmacia));

        when(bankTransactionRepository.sumByCategoryShared(any(), any(), any(), anyCollection(), anyCollection(), anyBoolean()))
                .thenReturn(List.of());
        when(bankTransactionRepository.findSharedInWindow(any(), any(), any(), anyCollection(), anyCollection(), anyBoolean()))
                .thenReturn(List.of());
    }

    // ------------------------------------------------------------ análise

    @Test
    @DisplayName("combined soma quem mostra algo; NONE aparece com totals nulo; eu entro por inteiro mesmo em NONE")
    void combinedIgnoraNoneEMeIncluiPorInteiro() {
        when(bankTransactionRepository.sumByCategoryShared(eq(ana.getId()), any(), any(),
                eq(Set.of()), eq(Set.of()), eq(true)))
                .thenReturn(List.of(
                        new Row(alimentacao.getId(), "DEBIT", new BigDecimal("-300.00"), 3),
                        new Row(null, "CREDIT", new BigDecimal("5000.00"), 1)));
        when(bankTransactionRepository.sumByCategoryShared(eq(bia.getId()), any(), any(),
                anyCollection(), anyCollection(), anyBoolean()))
                .thenReturn(List.of(
                        new Row(alimentacao.getId(), "DEBIT", new BigDecimal("-200.00"), 2),
                        new Row(petsDaBia.getId(), "DEBIT", new BigDecimal("-80.00"), 1)));

        FamilyAnalyticsResponse response = service.monthly(ANA, JULHO);

        assertThat(response.window().month()).isEqualTo("2026-07");
        assertThat(response.members()).hasSize(3);

        FamilyAnalyticsResponse.MemberAnalytics eu = response.members().get(0);
        assertThat(eu.isMe()).isTrue();
        assertThat(eu.shareScope()).isEqualTo("NONE");
        assertThat(eu.totals().income()).isEqualByComparingTo("5000.00");
        assertThat(eu.totals().expense()).isEqualByComparingTo("300.00");
        assertThat(eu.totals().net()).isEqualByComparingTo("4700.00");

        FamilyAnalyticsResponse.MemberAnalytics caioBlock = response.members().get(2);
        assertThat(caioBlock.name()).isEqualTo("Caio");
        assertThat(caioBlock.totals()).isNull();
        assertThat(caioBlock.categories()).isEmpty();
        // NONE não é consultado: nada dele sai do banco, nem para descartar
        verify(bankTransactionRepository, never()).sumByCategoryShared(eq(caio.getId()),
                any(), any(), anyCollection(), anyCollection(), anyBoolean());

        assertThat(response.combined().income()).isEqualByComparingTo("5000.00");
        assertThat(response.combined().expense()).isEqualByComparingTo("580.00");
        assertThat(response.combined().net()).isEqualByComparingTo("4420.00");
        // a categoria do sistema funde os dois; a pessoal da Bia fica separada
        assertThat(response.combined().categories())
                .extracting(FamilyAnalyticsResponse.CategorySlice::categoryName,
                        FamilyAnalyticsResponse.CategorySlice::expense)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Alimentação", new BigDecimal("500.00")),
                        org.assertj.core.groups.Tuple.tuple("Pets", new BigDecimal("80.00")),
                        org.assertj.core.groups.Tuple.tuple("Sem categoria", BigDecimal.ZERO));
    }

    @Test
    @DisplayName("O nome da categoria pessoal vem do catálogo do DONO da linha")
    void nomeDaCategoriaPessoalVemDoDono() {
        when(bankTransactionRepository.sumByCategoryShared(eq(bia.getId()), any(), any(),
                anyCollection(), anyCollection(), anyBoolean()))
                .thenReturn(List.of(new Row(petsDaBia.getId(), "DEBIT", new BigDecimal("-80.00"), 1)));

        FamilyAnalyticsResponse response = service.monthly(ANA, JULHO);

        // "Pets" não existe no catálogo da Ana; sem o nome na resposta a tela
        // dela mostraria um id
        FamilyAnalyticsResponse.MemberAnalytics biaBlock = response.members().get(1);
        assertThat(biaBlock.categories()).hasSize(1);
        assertThat(biaBlock.categories().get(0).categoryId()).isEqualTo(petsDaBia.getId());
        assertThat(biaBlock.categories().get(0).categoryName()).isEqualTo("Pets");
        assertThat(biaBlock.categories().get(0).txCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Folha e raiz do mesmo membro somam numa linha só, a da raiz — como na análise pessoal")
    void folhaERaizSomamNaRaiz() {
        when(bankTransactionRepository.sumByCategoryShared(eq(ana.getId()), any(), any(),
                anyCollection(), anyCollection(), anyBoolean()))
                .thenReturn(List.of(
                        new Row(saude.getId(), "DEBIT", new BigDecimal("-200.00"), 2),
                        new Row(farmacia.getId(), "DEBIT", new BigDecimal("-100.00"), 1),
                        new Row(alimentacao.getId(), "DEBIT", new BigDecimal("-50.00"), 1)));

        FamilyAnalyticsResponse response = service.monthly(ANA, JULHO);

        // "Eu" mostra "Saúde 300"; se "Casa" mostrasse "Farmácia 100 + Saúde
        // 200" para as mesmas linhas, a tela leria como erro
        FamilyAnalyticsResponse.MemberAnalytics eu = response.members().get(0);
        assertThat(eu.categories())
                .extracting(FamilyAnalyticsResponse.CategorySlice::categoryId,
                        FamilyAnalyticsResponse.CategorySlice::categoryName,
                        FamilyAnalyticsResponse.CategorySlice::expense,
                        FamilyAnalyticsResponse.CategorySlice::txCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(saude.getId(), "Saúde", new BigDecimal("300.00"), 3L),
                        org.assertj.core.groups.Tuple.tuple(alimentacao.getId(), "Alimentação", new BigDecimal("50.00"), 1L));
        assertThat(eu.totals().expense()).isEqualByComparingTo("350.00");
        // o combinado agrega pela mesma raiz
        assertThat(response.combined().categories())
                .extracting(FamilyAnalyticsResponse.CategorySlice::categoryId)
                .containsExactly(saude.getId(), alimentacao.getId());
        assertThat(response.combined().categories().get(0).expense()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("Os filtros do membro descem para a consulta — e ocultar a raiz oculta as filhas")
    void filtrosDoMembroDescemParaAConsulta() {
        UUID nubank = UUID.randomUUID();
        biaTransactions.getSharedAccountIds().add(nubank);
        biaTransactions.setIncludeUnassigned(false);

        service.monthly(ANA, JULHO);

        // Saúde foi ocultada; a linha da farmácia carrega o id da FILHA, e sem
        // a expansão o gasto escondido voltaria pela subcategoria
        verify(bankTransactionRepository).sumByCategoryShared(eq(bia.getId()),
                eq(JULHO.startInstant()), eq(JULHO.endExclusiveInstant()),
                argThat((Collection<UUID> hidden) -> hidden.containsAll(Set.of(saude.getId(), farmacia.getId()))
                        && !hidden.contains(alimentacao.getId())),
                eq(Set.of(nubank)), eq(false));
        // ... e o total pessoal (sem filtro) nunca é consultado para ninguém:
        // o total do membro na casa nasce das mesmas linhas filtradas
        verify(bankTransactionRepository, never()).sumByCategory(any(), any(), any());
    }

    @Test
    @DisplayName("Tipo fora do padrão OFX decide pelo sinal, como na análise pessoal")
    void tipoForaDoPadraoDecidePeloSinal() {
        when(bankTransactionRepository.sumByCategoryShared(eq(ana.getId()), any(), any(),
                anyCollection(), anyCollection(), anyBoolean()))
                .thenReturn(List.of(
                        new Row(alimentacao.getId(), "OTHER", new BigDecimal("30.00"), 1),
                        new Row(alimentacao.getId(), "PAYMENT", new BigDecimal("-80.00"), 1)));

        FamilyAnalyticsResponse response = service.monthly(ANA, JULHO);

        assertThat(response.members().get(0).totals().income()).isEqualByComparingTo("30.00");
        assertThat(response.members().get(0).totals().expense()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Sem casa, 404")
    void semCasa404() {
        when(memberRepository.findByUserId(ana.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.monthly(ANA, JULHO)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.transactions(ANA, JULHO, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------ extrato

    @Test
    @DisplayName("O extrato da casa traz as minhas linhas e as de quem abriu TRANSACTIONS, por data")
    void extratoTrazMinhasEDeQuemAbriu() {
        FamilyMember daniTotals = member(
                User.builder().id(UUID.randomUUID()).name("Dani").email("d@e.dev").password("x").build(),
                FamilyMember.Role.MEMBER, FamilyMember.ShareScope.TOTALS);
        when(memberRepository.findAllByGroupIdOrderByJoinedAtAsc(casa.getId()))
                .thenReturn(List.of(anaMe, biaTransactions, caioNone, daniTotals));
        when(bankTransactionRepository.findSharedInWindow(eq(ana.getId()), any(), any(),
                eq(Set.of()), eq(Set.of()), eq(true)))
                .thenReturn(List.of(tx(ana, "-10.00", 5)));
        when(bankTransactionRepository.findSharedInWindow(eq(bia.getId()), any(), any(),
                anyCollection(), anyCollection(), anyBoolean()))
                .thenReturn(List.of(tx(bia, "-20.00", 20), tx(bia, "-30.00", 1)));

        List<FamilyTransactionResponse> rows = service.transactions(ANA, JULHO, null, null);

        assertThat(rows).extracting(FamilyTransactionResponse::memberName)
                .containsExactly("Bia", "Ana", "Bia");
        assertThat(rows.get(0).memberId()).isEqualTo(biaTransactions.getId());
        assertThat(rows.get(0).amount()).isEqualByComparingTo("-20.00");
        // TOTALS e NONE não têm linha na casa — e nem são consultados
        verify(bankTransactionRepository, never()).findSharedInWindow(eq(daniTotals.getUser().getId()),
                any(), any(), anyCollection(), anyCollection(), anyBoolean());
        verify(bankTransactionRepository, never()).findSharedInWindow(eq(caio.getId()),
                any(), any(), anyCollection(), anyCollection(), anyBoolean());
    }

    @Test
    @DisplayName("memberId restringe a um membro; categoryId recorta; membro de outra casa é 404")
    void filtrosDoExtrato() {
        BankTransaction comida = tx(bia, "-20.00", 20);
        comida.setCategoryId(alimentacao.getId());
        BankTransaction pets = tx(bia, "-30.00", 1);
        pets.setCategoryId(petsDaBia.getId());
        when(bankTransactionRepository.findSharedInWindow(eq(bia.getId()), any(), any(),
                anyCollection(), anyCollection(), anyBoolean()))
                .thenReturn(List.of(comida, pets));

        List<FamilyTransactionResponse> soBia = service.transactions(ANA, JULHO, biaTransactions.getId(), null);

        assertThat(soBia).hasSize(2);
        assertThat(soBia).allMatch(r -> r.memberName().equals("Bia"));
        // com memberId, só aquele membro é consultado — nem eu mesma
        verify(bankTransactionRepository, never()).findSharedInWindow(eq(ana.getId()),
                any(), any(), anyCollection(), anyCollection(), anyBoolean());

        List<FamilyTransactionResponse> soPets = service.transactions(ANA, JULHO, null, petsDaBia.getId());
        assertThat(soPets).hasSize(1);
        assertThat(soPets.get(0).categoryId()).isEqualTo(petsDaBia.getId());

        UUID deOutraCasa = UUID.randomUUID();
        when(memberRepository.findByIdAndGroupId(deOutraCasa, casa.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transactions(ANA, JULHO, deOutraCasa, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------ apoio

    private FamilyMember member(User user, FamilyMember.Role role, FamilyMember.ShareScope scope) {
        return FamilyMember.builder().id(UUID.randomUUID()).group(casa).user(user)
                .role(role).shareScope(scope).joinedAt(OffsetDateTime.now()).build();
    }

    private static Category system(String name) {
        return Category.builder().id(UUID.randomUUID()).name(name).slug(name.toLowerCase())
                .flow(Category.Flow.EXPENSE).build();
    }

    private static Category childOf(Category parent, String name) {
        return Category.builder().id(UUID.randomUUID()).name(name).slug(name.toLowerCase())
                .parent(parent).flow(Category.Flow.EXPENSE).build();
    }

    private static BankTransaction tx(User owner, String amount, int day) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(owner)
                .transactionId("ext-" + UUID.randomUUID())
                .type("DEBIT")
                .amount(new BigDecimal(amount))
                .description("Lancamento")
                .date(OffsetDateTime.of(2026, 7, day, 12, 0, 0, 0, ZoneOffset.UTC))
                .reviewStatus(BankTransaction.ReviewStatus.CONFIRMED)
                .build();
    }
}
