package br.com.economize.service;

import br.com.economize.dto.ai.ChatTurn;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.Transaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.TransactionRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.ai.AiChatCaller;
import br.com.economize.service.ai.AiChatCallerFactory;
import br.com.economize.service.ai.AssistantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiAssistantService {

    // EC-107: quem escolhe a chave e o provedor da chamada. Sem configuração
    // própria, o factory devolve exatamente o ChatClient do servidor que este
    // serviço construía sozinho antes — o comportamento de quem não mexeu em
    // nada é o mesmo, linha por linha do prompt.
    private final AiChatCallerFactory chatCallerFactory;
    private final UserRepository userRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;

    public AiAssistantService(AiChatCallerFactory chatCallerFactory,
                              UserRepository userRepository,
                              BankTransactionRepository bankTransactionRepository,
                              TransactionRepository transactionRepository,
                              CategoryRepository categoryRepository) {
        this.chatCallerFactory = chatCallerFactory;
        this.userRepository = userRepository;
        this.bankTransactionRepository = bankTransactionRepository;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
    }

    /**
     * Quantos dias de extrato entram no contexto.
     *
     * <p>Noventa: e o recorte que a sincronizacao traz e cobre "este mes",
     * "mes passado" e a comparacao entre os dois, que sao as tres perguntas
     * que aparecem. A janela e DECLARADA no prompt — mandar soma sem periodo
     * era o que fazia "quanto gastei em setembro" ser respondido com o total
     * de dois anos.
     */
    static final int CONTEXT_DAYS = 90;

    public Mono<String> askAssistant(String email, String userQuestion) {
        return askAssistant(email, userQuestion, List.of());
    }

    /**
     * A pergunta com a conversa ate aqui.
     *
     * <p>Sem o historico o assistente nao tinha memoria nenhuma: "e no mes
     * passado?" chegava ao provedor como uma primeira pergunta solta, e a
     * resposta era necessariamente sobre nada. O dado financeiro do prompt
     * continua vindo do BANCO a cada chamada — quem manda o historico e o app,
     * mas quem responde pelos numeros e o servidor.
     */
    public Mono<String> askAssistant(String email, String userQuestion, List<ChatTurn> history) {
        return Mono.fromCallable(() -> {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));

            List<Transaction> walletTxs = transactionRepository.findAllByUserIdOrderByTransactionDateDesc(user.getId());
            String context = buildFinancialContext(user, walletTxs).render();

            String systemPromptText = """
                    Você é Nino, o assistente financeiro virtual do aplicativo Economize!.
                    Seu objetivo é ajudar o usuário a entender suas finanças, analisar gastos e dar dicas de investimentos.
                    Seja conciso, profissional, mas amigável. Responda em português do Brasil.
                    Use formatação Markdown para destacar valores e tópicos.

                    Aqui estão os dados financeiros atuais do usuário para contexto:
                    {context}

                    Regras:
                    - Baseie-se ESTRITAMENTE nos dados acima. Eles são os MESMOS números que as
                      telas do app mostram; se você disser um número diferente, o usuário vê a
                      contradição na mesma sessão e perde a confiança nos dois.
                    - Toda afirmação sobre valor tem de vir acompanhada dos LANÇAMENTOS que a
                      sustentam (data, descrição e valor), tirados da lista acima.
                    - Se a resposta não estiver nos dados, diga exatamente o que faltou e em que
                      período você procurou. NUNCA invente número, categoria ou lançamento.
                    - NUNCA peça ao usuário para conectar contas ou importar extrato quando já
                      houver lançamentos no contexto: ele já fez isso, e repetir o pedido é a
                      forma mais rápida de parecer que o app não leu os próprios dados.
                    - Se perguntarem sobre período fora da janela declarada acima, diga que a
                      janela é essa em vez de responder com o que tem.
                    - Se o usuário perguntar algo fora do escopo financeiro, recuse educadamente.
                    - Não recomende compra/venda direta de ativos específicos, apenas dê
                      orientações gerais.
                    """;

            // render() em vez de createMessage(): produz o MESMO texto de sistema
            // que o SystemPromptTemplate montava, só que como String — é o que o
            // AiChatCaller consome, seja ele o do servidor ou o do usuário
            String systemPrompt = new SystemPromptTemplate(systemPromptText)
                    .render(Map.of("context", context));

            // O assistente ACEITA cair na chave do servidor: é o comportamento
            // que o APK publicado conhece e não pode mudar para quem não
            // configurou nada. Por isso resolve(..., true) sempre traz um caller.
            AiChatCaller caller = chatCallerFactory.resolve(user, true)
                    .orElseThrow(() -> new IllegalStateException("Nenhum caminho de IA disponível"));

            // O id, e não o e-mail: este log escapou da troca anterior porque a
            // frase não seguia o padrão `user={}`, e era o único ponto que ainda
            // carregava dado pessoal para o log
            log.info("Enviando prompt para a IA: user={} ({}, {} fala(s) de contexto)",
                    user.getId(), caller.describe(), history.size());
            return caller.complete(systemPrompt, history, userQuestion);

        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * O contexto, montado com o MESMO filtro das telas.
     *
     * <p>A cláusula de exclusão é a linha mais importante deste serviço: sem
     * ela, o total que a IA recebia incluia transferência entre contas do
     * próprio dono, aplicação e resgate, par de estorno e duplicata
     * descartada. Medido no extrato real: só o movimento conta ↔ investimento
     * são R$ 39.216,06 — um número que <b>não existe em nenhuma tela</b>, e
     * que fazia qualquer resposta contradizer o painel por construção.
     */
    AssistantContext buildFinancialContext(User user, List<Transaction> walletTxs) {
        LocalDate hoje = LocalDate.now(ZoneOffset.UTC);
        LocalDate inicio = hoje.minusDays(CONTEXT_DAYS);
        OffsetDateTime de = inicio.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime ate = hoje.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        List<BankTransaction> naJanela = bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        user.getId(), de, ate);
        // As marcas aplicadas AQUI, e não na consulta, porque o número de
        // excluídos vai no prompt: a IA precisa poder explicar a diferença
        // entre o extrato e a soma em vez de fingir que ela não existe
        List<BankTransaction> contam = naJanela.stream()
                .filter(tx -> !tx.isInternalTransfer() && !tx.isIgnored()
                        && !tx.isRefunded() && !tx.isFamilyTransfer())
                .toList();

        Map<UUID, Category> categorias = categoryRepository.findVisibleTo(user.getId()).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity(), (a, b) -> a));

        return AssistantContext.of(inicio, hoje, contam, naJanela.size() - contam.size(),
                categorias, walletTxs);
    }
}
