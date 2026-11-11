package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.StatementUpload;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.StatementUploadRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.event.DomainEventPublisher;
import br.com.economize.service.statement.category.AiCategorySuggester;
import br.com.economize.service.statement.category.CategorizationEngine;
import br.com.economize.service.statement.category.DescriptionNormalizer;
import br.com.economize.service.statement.parser.ParsedTransaction;
import br.com.economize.service.statement.parser.StatementFormat;
import br.com.economize.service.statement.parser.StatementParserFactory;
import br.com.economize.service.statement.parser.StatementParserStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Os caminhos de ENTRADA da importação (EC-155): arquivo repetido, arquivo
 * vazio e a porta dos conectores.
 *
 * <p>Os testes que já existiam cobrem a reconciliação — o miolo. Faltavam as
 * bordas, que são as que o usuário encontra primeiro: reimportar o mesmo
 * extrato (o gesto mais comum de quem não lembra se já importou) e mandar um
 * arquivo que não tem transação nenhuma.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankStatementService — as bordas da importação")
class BankStatementImportPathsTest {

    private static final String EMAIL = "teste@economize.app";

    @Mock
    private BankTransactionRepository bankTransactionRepository;
    @Mock
    private StatementUploadRepository statementUploadRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private StatementParserFactory parserFactory;
    @Mock
    private CategorizationEngine categorizationEngine;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private StatementImportWriter importWriter;

    // So entra em cena quando o upload declara a origem (?accountId=)
    @Mock
    private ConnectorAccountService accountService;
    @Mock
    private DomainEventPublisher eventPublisher;
    @Mock
    private CategorizationEngine.Context context;
    @Mock
    private StatementParserStrategy parser;

    private BankStatementService service;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Teste").password("x").build();
        @SuppressWarnings("unchecked")
        ObjectProvider<AiCategorySuggester> aiSuggester = mock(ObjectProvider.class);
        service = new BankStatementService(bankTransactionRepository, statementUploadRepository,
                userRepository, parserFactory, categorizationEngine, categoryRepository,
                importWriter, accountService, eventPublisher, aiSuggester);

        lenient().when(categorizationEngine.contextFor(user.getId())).thenReturn(context);
        lenient().when(context.getDirtyRules()).thenReturn(new HashSet<>());
        lenient().when(categorizationEngine.categorize(eq(context), anyString(), anyString(), anyBoolean()))
                .thenAnswer(inv -> new CategorizationEngine.Result(
                        null, null, null, DescriptionNormalizer.normalize(inv.getArgument(1))));
        lenient().when(importWriter.write(any(StatementUpload.class), anyList(), anyCollection()))
                .thenAnswer(inv -> {
                    StatementUpload upload = inv.getArgument(0);
                    upload.setId(UUID.randomUUID());
                    return upload;
                });
        lenient().when(bankTransactionRepository
                        .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                                any(), any(), any()))
                .thenReturn(List.of());
    }

    /**
     * Um {@link FilePart} de verdade, com o conteúdo em memória: a rota real
     * recebe o arquivo por aqui, e é este caminho que decide formato e hash.
     */
    private FilePart arquivo(String nome, byte[] conteudo) {
        FilePart part = mock(FilePart.class);
        DataBuffer buffer = new DefaultDataBufferFactory().wrap(conteudo);
        lenient().when(part.filename()).thenReturn(nome);
        lenient().when(part.content()).thenReturn(Flux.just(buffer));
        return part;
    }

    private BankStatementService.ImportResult importar(String nome, byte[] conteudo) {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        return service.processFile(EMAIL, arquivo(nome, conteudo)).block();
    }

    private ParsedTransaction tx(String id, String valor, String descricao) {
        return ParsedTransaction.builder()
                .externalId(id)
                .type(new BigDecimal(valor).signum() < 0 ? "DEBIT" : "CREDIT")
                .amount(new BigDecimal(valor))
                .description(descricao)
                .date(OffsetDateTime.of(2026, 5, 10, 12, 0, 0, 0, ZoneOffset.UTC))
                .build();
    }

    // ------------------------------------------------ o mesmo arquivo de novo

    @Test
    @DisplayName("Reimportar o mesmo arquivo devolve o resultado ORIGINAL, sem gravar de novo")
    void arquivoRepetidoNaoDuplica() {
        StatementUpload anterior = new StatementUpload();
        anterior.setId(UUID.randomUUID());
        anterior.setTransactionsImported(7);
        when(statementUploadRepository.findByUserIdAndFileHash(eq(user.getId()), anyString()))
                .thenReturn(Optional.of(anterior));
        when(bankTransactionRepository.findAllByUserIdAndUploadIdOrderByDateDesc(
                user.getId(), anterior.getId()))
                .thenReturn(List.of(
                        comStatus(BankTransaction.ReviewStatus.SUGGESTED),
                        comStatus(BankTransaction.ReviewStatus.SUGGESTED),
                        comStatus(BankTransaction.ReviewStatus.UNCATEGORIZED),
                        comStatus(BankTransaction.ReviewStatus.CONFIRMED)));

        BankStatementService.ImportResult resultado = importar("extrato.csv", new byte[] { 1, 2, 3 });

        assertThat(resultado.duplicated()).isTrue();
        assertThat(resultado.uploadId()).isEqualTo(anterior.getId());
        // O número que volta é o da importação ORIGINAL, e a contagem de
        // pendências é a de AGORA: reimportar não pode dizer "0 importadas" a
        // quem só quer saber se o extrato já entrou
        assertThat(resultado.transactionsImported()).isEqualTo(7);
        assertThat(resultado.suggested()).isEqualTo(2);
        assertThat(resultado.uncategorized()).isEqualTo(1);

        verify(importWriter, never()).write(any(), anyList(), anyCollection());
    }

    private BankTransaction comStatus(BankTransaction.ReviewStatus status) {
        return BankTransaction.builder().id(UUID.randomUUID()).reviewStatus(status).build();
    }

    // ------------------------------------------------------- arquivo inútil

    @Test
    @DisplayName("Arquivo sem transação nenhuma é erro do cliente, com mensagem que explica")
    void arquivoSemTransacaoERecusado() {
        when(statementUploadRepository.findByUserIdAndFileHash(eq(user.getId()), anyString()))
                .thenReturn(Optional.empty());
        when(parserFactory.resolve(any(StatementFormat.class))).thenReturn(parser);
        when(parser.parse(any())).thenReturn(List.of());

        // `block()` republica RuntimeException como ela é, sem embrulhar
        assertThatThrownBy(() -> importar("vazio.csv", new byte[] { 1 }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nenhuma transação");

        // Gravar um upload de zero linhas encheria o histórico de importações
        // com registros que não representam nada
        verify(importWriter, never()).write(any(), anyList(), anyCollection());
    }

    @Test
    @DisplayName("O formato sai do NOME do arquivo, e é ele que escolhe o parser")
    void formatoVemDoNomeDoArquivo() {
        when(statementUploadRepository.findByUserIdAndFileHash(eq(user.getId()), anyString()))
                .thenReturn(Optional.empty());
        when(parserFactory.resolve(any(StatementFormat.class))).thenReturn(parser);
        when(parser.parse(any())).thenReturn(List.of(tx("ext-1", "-45.90", "MERCADO")));

        BankStatementService.ImportResult resultado = importar("extrato-maio.ofx", new byte[] { 1 });

        verify(parserFactory).resolve(StatementFormat.OFX);
        assertThat(resultado.format()).isEqualTo("OFX");
        assertThat(resultado.duplicated()).isFalse();
    }

    // ------------------------------------------------------- via conector

    @Test
    @DisplayName("Sincronização sem novidade não vira upload no histórico")
    void conectorSemNovidadeNaoGravaUpload() {
        BankStatementService.ImportResult resultado =
                service.importFromConnector(user, "Nubank", StatementFormat.OFX, List.of());

        assertThat(resultado.transactionsImported()).isZero();
        assertThat(resultado.uploadId()).isNull();
        assertThat(resultado.duplicated()).isFalse();
        verify(importWriter, never()).write(any(), anyList(), anyCollection());
    }

    @Test
    @DisplayName("Cada sincronização do conector é um registro próprio, mesmo com o mesmo conteúdo")
    void conectorGravaCadaSincronizacao() {
        List<ParsedTransaction> lote = List.of(tx("ext-1", "-45.90", "MERCADO"));

        service.importFromConnector(user, "Nubank", StatementFormat.OFX, lote);
        service.importFromConnector(user, "Nubank", StatementFormat.OFX, lote);

        // O hash sintético é único por sincronização: sem isso a segunda cairia
        // no caminho de "arquivo repetido" e a sync viraria silêncio
        verify(importWriter, org.mockito.Mockito.times(2))
                .write(any(StatementUpload.class), anyList(), anyCollection());
    }

    @Test
    @DisplayName("A dedupe por id externo não regrava o que já está no banco")
    void idExternoJaConhecidoNaoRegrava() {
        BankTransaction jaExiste = BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .transactionId("ext-1")
                .type("DEBIT")
                .amount(new BigDecimal("-45.90"))
                .description("MERCADO")
                .date(OffsetDateTime.of(2026, 5, 10, 12, 0, 0, 0, ZoneOffset.UTC))
                .reviewStatus(BankTransaction.ReviewStatus.CONFIRMED)
                .build();
        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        any(), any(), any()))
                .thenReturn(List.of(jaExiste));

        BankStatementService.ImportResult resultado = service.importFromConnector(
                user, "Nubank", StatementFormat.OFX,
                List.of(tx("ext-1", "-45.90", "MERCADO"), tx("ext-2", "-10.00", "PADARIA")));

        // Só a linha nova entra; a conhecida é pulada pelo id externo
        assertThat(resultado.transactionsImported()).isEqualTo(1);
    }
    // ------------------------------------------- um upload por conexão (banco)

    private static final UUID CONTA_INTER = UUID.randomUUID();
    private static final UUID CONTA_NUBANK = UUID.randomUUID();
    private static final BankStatementService.UploadSource INTER =
            new BankStatementService.UploadSource("item-1", "Inter");
    private static final BankStatementService.UploadSource NUBANK =
            new BankStatementService.UploadSource("item-2", "Nubank");
    private static final Map<UUID, BankStatementService.UploadSource> DUAS_CONEXOES =
            Map.of(CONTA_INTER, INTER, CONTA_NUBANK, NUBANK);

    private ParsedTransaction tx(String id, String valor, String descricao, UUID conta) {
        return tx(id, valor, descricao).toBuilder().accountId(conta).build();
    }

    /** O que foi entregue ao writer, um registro por chamada, na ordem. */
    private record Gravacao(StatementUpload upload, List<BankTransaction> linhas) {
    }

    private List<Gravacao> gravacoes(int vezes) {
        ArgumentCaptor<StatementUpload> uploads = ArgumentCaptor.forClass(StatementUpload.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BankTransaction>> linhas = ArgumentCaptor.forClass(List.class);
        verify(importWriter, times(vezes)).write(uploads.capture(), linhas.capture(), anyCollection());
        List<Gravacao> todas = new ArrayList<>();
        for (int i = 0; i < uploads.getAllValues().size(); i++) {
            todas.add(new Gravacao(uploads.getAllValues().get(i), linhas.getAllValues().get(i)));
        }
        return todas;
    }

    @Test
    @DisplayName("Duas conexões viram dois uploads: rótulo do banco e contagem de cada uma")
    void duasConexoesViramDoisUploads() {
        BankStatementService.ImportResult resultado = service.importFromConnector(
                user, StatementFormat.PLUGGY,
                List.of(tx("PLUGGY-n1", "-30.00", "IFOOD", CONTA_NUBANK),
                        tx("PLUGGY-i1", "-45.90", "MERCADO", CONTA_INTER),
                        tx("PLUGGY-n2", "-12.00", "PADARIA", CONTA_NUBANK)),
                DUAS_CONEXOES);

        List<Gravacao> gravadas = gravacoes(2);
        // a ordem dos uploads é a ordem em que cada conexão aparece no lote
        assertThat(gravadas).extracting(g -> g.upload().getFileName()).containsExactly("Nubank", "Inter");
        assertThat(gravadas).extracting(g -> g.upload().getTransactionsImported()).containsExactly(2, 1);
        assertThat(gravadas.get(0).linhas()).extracting(BankTransaction::getTransactionId)
                .containsExactly("PLUGGY-n1", "PLUGGY-n2");
        assertThat(gravadas.get(1).linhas()).extracting(BankTransaction::getTransactionId)
                .containsExactly("PLUGGY-i1");
        // cada sincronização de cada conexão é um registro próprio no histórico
        assertThat(gravadas.get(0).upload().getFileHash()).isNotEqualTo(gravadas.get(1).upload().getFileHash());

        // a procedência (V41) é da LINHA e não muda com o rótulo do upload
        assertThat(gravadas).flatExtracting(Gravacao::linhas)
                .extracting(BankTransaction::getImportSourceStored)
                .containsOnly(BankTransaction.ImportSource.CONNECTION);

        // a resposta soma as conexões e aponta o upload que mais trouxe novidade
        assertThat(resultado.transactionsImported()).isEqualTo(3);
        assertThat(resultado.uploadId()).isEqualTo(gravadas.get(0).upload().getId());
        assertThat(resultado.format()).isEqualTo("PLUGGY");
    }

    @Test
    @DisplayName("Mesmo dia e mesmo valor em bancos diferentes: as duas compras entram")
    void mesmoDiaEValorEmBancosDiferentesNaoViraDuplicata() {
        // É o caso que proíbe importar conexão por conexão: a segunda leria a
        // linha recém-gravada da primeira como "já existe" (dia + valor) e uma
        // compra de verdade sumiria. A reconciliação olha o lote inteiro de uma
        // vez, então o que acabou de chegar não reconcilia contra si mesmo.
        service.importFromConnector(user, StatementFormat.PLUGGY,
                List.of(tx("PLUGGY-i1", "-50.00", "POSTO SHELL", CONTA_INTER),
                        tx("PLUGGY-n1", "-50.00", "POSTO IPIRANGA", CONTA_NUBANK)),
                DUAS_CONEXOES);

        assertThat(gravacoes(2)).extracting(g -> g.upload().getTransactionsImported()).containsExactly(1, 1);
        // uma leitura só do que já existia: nenhuma conexão enxerga a outra
        verify(bankTransactionRepository, times(1))
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(any(), any(), any());
    }

    @Test
    @DisplayName("A segunda sincronização seguida importa zero em cada conexão")
    void segundaSincronizacaoImportaZero() {
        List<ParsedTransaction> lote = List.of(
                tx("PLUGGY-i1", "-45.90", "MERCADO", CONTA_INTER),
                tx("PLUGGY-n1", "-30.00", "IFOOD", CONTA_NUBANK));
        service.importFromConnector(user, StatementFormat.PLUGGY, lote, DUAS_CONEXOES);
        List<BankTransaction> jaGravadas = gravacoes(2).stream()
                .flatMap(g -> g.linhas().stream())
                .peek(linha -> linha.setId(UUID.randomUUID()))
                .toList();
        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(any(), any(), any()))
                .thenReturn(jaGravadas);

        BankStatementService.ImportResult segunda =
                service.importFromConnector(user, StatementFormat.PLUGGY, lote, DUAS_CONEXOES);

        assertThat(segunda.transactionsImported()).isZero();
        // pulado pelo id externo, não "reconciliado": é a mesma linha, não outra parecida
        assertThat(segunda.reconciled()).isZero();
        // as duas conexões continuam registrando que foram sincronizadas, vazias
        List<Gravacao> todas = gravacoes(4);
        assertThat(todas.subList(2, 4)).extracting(g -> g.upload().getFileName())
                .containsExactly("Inter", "Nubank");
        assertThat(todas.subList(2, 4)).allSatisfy(g -> assertThat(g.linhas()).isEmpty());
    }

    @Test
    @DisplayName("Arquivo x conector: o que o OFX já trouxe não entra de novo, em nenhuma das conexões")
    void arquivoJaImportadoReconciliaComCadaConexao() {
        BankTransaction doArquivo = BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .transactionId("OFX-777")
                .type("DEBIT")
                .amount(new BigDecimal("-45.90"))
                .description("MERCADO")
                .normalizedDescription(DescriptionNormalizer.normalize("MERCADO"))
                .date(OffsetDateTime.of(2026, 5, 10, 9, 0, 0, 0, ZoneOffset.UTC))
                .reviewStatus(BankTransaction.ReviewStatus.CONFIRMED)
                .build();
        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(any(), any(), any()))
                .thenReturn(List.of(doArquivo));

        BankStatementService.ImportResult resultado = service.importFromConnector(
                user, StatementFormat.PLUGGY,
                List.of(tx("PLUGGY-i1", "-45.90", "MERCADO", CONTA_INTER),
                        tx("PLUGGY-n1", "-30.00", "IFOOD", CONTA_NUBANK)),
                DUAS_CONEXOES);

        assertThat(resultado.reconciled()).isEqualTo(1);
        assertThat(resultado.transactionsImported()).isEqualTo(1);
        List<Gravacao> gravadas = gravacoes(2);
        assertThat(gravadas.get(0).upload().getFileName()).isEqualTo("Inter");
        assertThat(gravadas.get(0).linhas()).isEmpty();
        assertThat(gravadas.get(1).linhas()).extracting(BankTransaction::getTransactionId)
                .containsExactly("PLUGGY-n1");
    }

    @Test
    @DisplayName("Falha parcial: a conexão que gravou fica, a outra é tentada, e a chamada termina em erro")
    void falhaDeUmaConexaoNaoPerdeAsOutrasNemPassaEmSilencio() {
        // COMPORTAMENTO ESCOLHIDO. Cada conexão é um commit próprio. Se uma
        // falha: (1) as que já gravaram continuam gravadas — nada é desfeito;
        // (2) as seguintes ainda são tentadas; (3) a chamada lança o erro
        // original, para a sincronização não responder "ok" com um banco
        // faltando; (4) as remarcações de pós-importação não rodam sobre um
        // lote pela metade. A sincronização seguinte pula pelo id externo o
        // que entrou e importa só o que faltou.
        when(importWriter.write(any(StatementUpload.class), anyList(), anyCollection()))
                .thenAnswer(inv -> {
                    StatementUpload upload = inv.getArgument(0);
                    if ("Inter".equals(upload.getFileName())) {
                        throw new IllegalStateException("banco fora do ar");
                    }
                    upload.setId(UUID.randomUUID());
                    return upload;
                });

        assertThatThrownBy(() -> service.importFromConnector(user, StatementFormat.PLUGGY,
                List.of(tx("PLUGGY-i1", "-45.90", "MERCADO", CONTA_INTER),
                        tx("PLUGGY-n1", "-30.00", "IFOOD", CONTA_NUBANK)),
                DUAS_CONEXOES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("banco fora do ar");

        // o Nubank veio DEPOIS da falha do Inter e mesmo assim foi gravado
        List<Gravacao> tentativas = gravacoes(2);
        assertThat(tentativas).extracting(g -> g.upload().getFileName()).containsExactly("Inter", "Nubank");
        // só o que de fato gravou é anunciado: um evento, o do Nubank
        verify(eventPublisher, times(1)).publish(any());
        verify(bankTransactionRepository, never()).markAsInternalTransfer(any(), any());
    }

    @Test
    @DisplayName("Conta fora do mapa de conexões cai no rótulo genérico")
    void contaSemConexaoConhecidaUsaRotuloGenerico() {
        service.importFromConnector(user, StatementFormat.PLUGGY,
                List.of(tx("PLUGGY-x1", "-45.90", "MERCADO", UUID.randomUUID()),
                        tx("PLUGGY-x2", "-10.00", "PADARIA")),
                DUAS_CONEXOES);

        List<Gravacao> gravadas = gravacoes(1);
        assertThat(gravadas.get(0).upload().getFileName()).isEqualTo("Meu Pluggy");
        assertThat(gravadas.get(0).linhas()).hasSize(2);
    }
}
