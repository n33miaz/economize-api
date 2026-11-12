package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.CategoryRule;
import br.com.economize.model.StatementUpload;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.CategoryRuleRepository;
import br.com.economize.repository.StatementUploadRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.event.DomainEventPublisher;
import br.com.economize.service.statement.category.AiCategorySuggester;
import br.com.economize.service.statement.category.CategorizationEngine;
import br.com.economize.service.statement.category.RuleBasedCategorizationService;
import br.com.economize.service.statement.parser.ParsedTransaction;
import br.com.economize.service.statement.parser.StatementFormat;
import br.com.economize.service.statement.parser.StatementParserFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * O acerto de uma regra é contado junto com a linha que o gerou — na gravação
 * da FONTE dela, e em nenhuma outra.
 *
 * <p>O defeito: a decisão de categoria é tomada sobre a sincronização inteira,
 * e os acertos do lote todo iam com a primeira conexão que gravasse. Inter
 * grava, a regra só tinha casado uma linha do Nubank, o Nubank falha: o acerto
 * já estava salvo. Na nova tentativa o Nubank entrava e somava de novo. A
 * métrica inflava, e mais a cada tentativa.
 *
 * <p>O motor aqui é o de verdade, de propósito: metade do conserto é ele ter
 * parado de somar na regra enquanto decide.
 *
 * <p>O writer é um dublê COM MEMÓRIA: só "grava" (linhas e acertos) quando não
 * lança, como a transação real faz. O que ele não prova é o rollback do banco
 * — ver {@code BankStatementImportWriterTest} para a soma de verdade.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankStatementService — acerto de regra conta com a fonte que gravou")
class BankStatementRuleHitsTest {

    private static final UUID CONTA_INTER = UUID.randomUUID();
    private static final UUID CONTA_NUBANK = UUID.randomUUID();
    private static final Map<UUID, BankStatementService.UploadSource> DUAS_CONEXOES = Map.of(
            CONTA_INTER, new BankStatementService.UploadSource("item-1", "Inter"),
            CONTA_NUBANK, new BankStatementService.UploadSource("item-2", "Nubank"));

    @Mock
    private BankTransactionRepository bankTransactionRepository;
    @Mock
    private StatementUploadRepository statementUploadRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private StatementParserFactory parserFactory;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private CategoryRuleRepository ruleRepository;
    @Mock
    private StatementImportWriter importWriter;
    @Mock
    private ConnectorAccountService accountService;
    @Mock
    private DomainEventPublisher eventPublisher;

    private BankStatementService service;
    private User user;
    private CategoryRule regra;

    /** O "banco" do dublê: o que as gravações que deram certo deixaram. */
    private final List<BankTransaction> linhasGravadas = new ArrayList<>();
    private final Map<UUID, Integer> acertosGravados = new HashMap<>();
    /** O que cada chamada ao writer recebeu, na ordem — inclusive as que falharam. */
    private final List<Entrega> entregas = new ArrayList<>();
    private final Set<String> fontesForaDoAr = new HashSet<>();

    private record Entrega(String fonte, Collection<StatementImportWriter.RuleHits> acertos) {
    }

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email("teste@economize.app").name("Teste")
                .password("x").build();
        Category delivery = Category.builder().id(UUID.randomUUID()).name("Delivery").slug("delivery")
                .flow(Category.Flow.EXPENSE).archived(false).build();
        regra = CategoryRule.builder().id(UUID.randomUUID()).category(delivery).pattern("ifood")
                .matchType(CategoryRule.MatchType.CONTAINS).origin(CategoryRule.Origin.USER).hits(5).build();

        CategorizationEngine engine = new CategorizationEngine(ruleRepository, categoryRepository,
                new RuleBasedCategorizationService());
        @SuppressWarnings("unchecked")
        ObjectProvider<AiCategorySuggester> aiSuggester = mock(ObjectProvider.class);
        service = new BankStatementService(bankTransactionRepository, statementUploadRepository,
                userRepository, parserFactory, engine, categoryRepository,
                importWriter, accountService, eventPublisher, aiSuggester);

        lenient().when(ruleRepository.findAllWithCategoryByUserId(user.getId())).thenReturn(List.of(regra));
        lenient().when(categoryRepository.findAllByUserIsNull()).thenReturn(List.of());
        // a janela devolve o que o dublê já "gravou": é por ela que a nova
        // tentativa reconhece o que entrou e importa só o que faltou
        lenient().when(bankTransactionRepository
                        .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<>(linhasGravadas));
        lenient().when(importWriter.write(any(StatementUpload.class), anyList(), anyCollection()))
                .thenAnswer(inv -> {
                    StatementUpload upload = inv.getArgument(0);
                    List<BankTransaction> linhas = inv.getArgument(1);
                    Collection<StatementImportWriter.RuleHits> acertos = inv.getArgument(2);
                    entregas.add(new Entrega(upload.getFileName(), List.copyOf(acertos)));
                    if (fontesForaDoAr.contains(upload.getFileName())) {
                        // transação desfeita: nem linha, nem acerto
                        throw new IllegalStateException("banco fora do ar");
                    }
                    upload.setId(UUID.randomUUID());
                    for (BankTransaction linha : linhas) {
                        linha.setId(UUID.randomUUID());
                        linhasGravadas.add(linha);
                    }
                    for (StatementImportWriter.RuleHits acerto : acertos) {
                        acertosGravados.merge(acerto.ruleId(), acerto.count(), Integer::sum);
                    }
                    return upload;
                });
    }

    private ParsedTransaction tx(String id, String valor, String descricao, UUID conta) {
        return ParsedTransaction.builder()
                .externalId(id)
                .type("DEBIT")
                .amount(new BigDecimal(valor))
                .description(descricao)
                .date(OffsetDateTime.of(2026, 5, 10, 12, 0, 0, 0, ZoneOffset.UTC))
                .accountId(conta)
                .build();
    }

    private void sincronizar(List<ParsedTransaction> lote) {
        service.importFromConnector(user, StatementFormat.PLUGGY, lote, DUAS_CONEXOES);
    }

    private int acertosDaRegra() {
        return acertosGravados.getOrDefault(regra.getId(), 0);
    }

    @Test
    @DisplayName("Regra casa só no Nubank e o Nubank falha: nenhum acerto gravado; a nova tentativa soma 1")
    void acertoNaoVaiComAFonteErrada() {
        List<ParsedTransaction> lote = List.of(
                tx("PLUGGY-i1", "-45.90", "MERCADO", CONTA_INTER),
                tx("PLUGGY-n1", "-30.00", "IFOOD", CONTA_NUBANK));
        fontesForaDoAr.add("Nubank");

        assertThatThrownBy(() -> sincronizar(lote)).isInstanceOf(IllegalStateException.class);

        // o Inter gravou, e NÃO levou o acerto de uma linha que não é dele
        assertThat(entregas).extracting(Entrega::fonte).containsExactly("Inter", "Nubank");
        assertThat(entregas.get(0).acertos()).isEmpty();
        assertThat(entregas.get(1).acertos())
                .containsExactly(new StatementImportWriter.RuleHits(regra.getId(), 1));
        assertThat(linhasGravadas).extracting(BankTransaction::getTransactionId).containsExactly("PLUGGY-i1");
        assertThat(acertosDaRegra()).isZero();

        // nova tentativa, banco de volta: a linha do Nubank entra, com o acerto dela
        fontesForaDoAr.clear();
        sincronizar(lote);

        assertThat(linhasGravadas).extracting(BankTransaction::getTransactionId)
                .containsExactly("PLUGGY-i1", "PLUGGY-n1");
        // +1 exato: antes do conserto eram 2 (um por tentativa)
        assertThat(acertosDaRegra()).isEqualTo(1);
    }

    @Test
    @DisplayName("Regra casa nas duas fontes e uma falha: cada tentativa grava só os acertos do que gravou")
    void regraNasDuasFontesContaPorFonte() {
        List<ParsedTransaction> lote = List.of(
                tx("PLUGGY-i1", "-45.90", "IFOOD MERCADO", CONTA_INTER),
                tx("PLUGGY-n1", "-30.00", "IFOOD", CONTA_NUBANK),
                tx("PLUGGY-n2", "-18.00", "IFOOD LANCHES", CONTA_NUBANK));
        fontesForaDoAr.add("Nubank");

        assertThatThrownBy(() -> sincronizar(lote)).isInstanceOf(IllegalStateException.class);

        // a regra é UM objeto para o lote todo: somar nela e salvar o total
        // gravaria 3 aqui, junto com o Inter
        assertThat(acertosDaRegra()).isEqualTo(1);

        fontesForaDoAr.clear();
        sincronizar(lote);

        assertThat(acertosDaRegra()).isEqualTo(3);
    }

    @Test
    @DisplayName("Caso feliz: as duas fontes gravam e a regra soma um acerto por linha que decidiu")
    void casoFelizContaComoSempre() {
        sincronizar(List.of(
                tx("PLUGGY-i1", "-45.90", "IFOOD MERCADO", CONTA_INTER),
                tx("PLUGGY-i2", "-9.00", "PADARIA", CONTA_INTER),
                tx("PLUGGY-n1", "-30.00", "IFOOD", CONTA_NUBANK),
                tx("PLUGGY-n2", "-18.00", "IFOOD LANCHES", CONTA_NUBANK)));

        assertThat(entregas).extracting(Entrega::fonte).containsExactly("Inter", "Nubank");
        assertThat(entregas.get(0).acertos())
                .containsExactly(new StatementImportWriter.RuleHits(regra.getId(), 1));
        assertThat(entregas.get(1).acertos())
                .containsExactly(new StatementImportWriter.RuleHits(regra.getId(), 2));
        assertThat(acertosDaRegra()).isEqualTo(3);
        // a decisão não mudou: as três linhas saem com a categoria da regra
        assertThat(linhasGravadas).filteredOn(l -> l.getCategorizedBy() == BankTransaction.CategorizedBy.USER_RULE)
                .extracting(BankTransaction::getTransactionId)
                .containsExactly("PLUGGY-i1", "PLUGGY-n1", "PLUGGY-n2");
        // e a cópia que o motor leu fica como veio: quem soma é o banco
        assertThat(regra.getHits()).isEqualTo(5);
    }
}
