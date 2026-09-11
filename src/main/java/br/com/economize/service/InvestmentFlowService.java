package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Aplicar não é gastar; resgatar não é ganhar.
 *
 * <p><b>O defeito que isto resolve.</b> Quando o dono manda R$ 400 da conta
 * corrente para o CDB dele, o extrato escreve uma saída de R$ 400 — e o app lê
 * "despesa de R$ 400". Quando o dinheiro volta, lê "receita". O dinheiro nunca
 * saiu do bolso dele: mudou de gaveta. É o mesmo raciocínio de
 * {@link InternalTransferService}, que já cobre conta ↔ conta pelo nome do
 * titular; aqui a outra ponta é um <b>investimento</b> do mesmo dono, e o
 * extrato não escreve nome nenhum — escreve o produto.
 *
 * <p><b>O tamanho do buraco, medido</b> no extrato do Inter do dono
 * (12/08/2024 a 11/08/2026, 1.682 lançamentos), em 10/09/2026:
 *
 * <table><caption>O que hoje entra nas somas e não devia</caption>
 *   <tr><td>Resgate</td><td>247 linhas</td><td>+13.710,69 lidos como receita</td></tr>
 *   <tr><td>Aplicação</td><td>69 linhas</td><td>−14.895,27 lidos como despesa</td></tr>
 *   <tr><td>Crédito Tesouro Direto</td><td>19 linhas</td><td>+5.282,93</td></tr>
 *   <tr><td>Estorno de Aplicação</td><td>6 linhas</td><td>+1.102,52</td></tr>
 *   <tr><td>Conta Global de Investimento</td><td>6 linhas</td><td>−683,74 e +2.714,77</td></tr>
 *   <tr><td>Débito Online TD / Débito Tesouro Direto</td><td>3 linhas</td><td>−826,14</td></tr>
 * </table>
 *
 * <p>São <b>350 linhas</b> e <b>R$ 39.216,06</b> de movimento que nunca
 * aconteceu — num extrato cujo Pix real soma R$ 48 mil de entrada. Os números
 * de gasto e de renda estão praticamente dobrados, e o saldo fecha, que é
 * justamente por que ninguém percebe.
 *
 * <p><b>As três armadilhas</b>, todas presentes no mesmo extrato, e todas
 * dinheiro de verdade que <b>não</b> pode sair da soma:
 *
 * <ol>
 *   <li>{@code Imposto | IR/IOF - Tesouro direto} — 20 linhas, R$ 73,80. Fala
 *       em Tesouro Direto, mas é imposto pago;</li>
 *   <li>{@code Debito Iof Conta Global De Inv} — 6 linhas, R$ 17,78. Fala no
 *       produto, mas é IOF;</li>
 *   <li>{@code Cred Pontos Meu Porquinho | Resgate Pontos} — 9 linhas,
 *       R$ 31,75. Diz "Resgate", mas resgatar pontos é receita de verdade.</li>
 * </ol>
 *
 * <p>Por isso a regra tem duas metades: um marcador de movimento de
 * investimento <b>e</b> a ausência de qualquer marca de custo ou prêmio. A
 * segunda metade é que impede o serviço de apagar imposto da conta do dono.
 *
 * <p><b>Palavra inteira, sempre.</b> O mesmo extrato tem
 * {@code Compra no débito | Jo<b>selic</b>e Do Nascimento} — uma compra numa
 * padaria que contém "selic" no meio do nome de alguém. É por isso que nenhum
 * marcador aqui é procurado por "contém": {@link CounterpartyMatcher#normalize}
 * limpa o texto e a comparação é cercada por espaço dos dois lados.
 *
 * <p><b>O que se perde, dito às claras.</b> Um resgate de CDB traz o principal
 * <i>mais o rendimento</i>, e o extrato não separa os dois. Tirando o resgate
 * inteiro da receita, o rendimento sai junto — a renda fica subestimada nesse
 * pedaço. A alternativa é contar os R$ 13.710,69 inteiros como renda, o que
 * erra por muito mais e na direção que engana. Quando a posição de investimento
 * existir por dentro (EC-165), o rendimento volta pela porta certa: como
 * rendimento, não como resgate.
 *
 * <p><b>Marca, não apaga</b>, e reusa {@code internal_transfer} em vez de criar
 * coluna nova: a pergunta que a coluna responde — "este dinheiro trocou de
 * bolso do mesmo dono?" — é exatamente esta. Rodar duas vezes é seguro; a
 * varredura nunca desmarca, e a decisão manual do usuário sempre vence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvestmentFlowService {

    /**
     * O que diz "dinheiro andando entre a conta e o investimento do dono".
     *
     * <p>Os cinco primeiros foram <b>medidos</b> no extrato do dono e cobrem as
     * 350 linhas. Os demais são nomes de produto que só um banco escreve num
     * extrato — foram testados contra todos os extratos do dono (Inter, Nubank,
     * Flash) e não produzem um único casamento a mais, então entram sem custo e
     * cobrem o dia em que outro banco for conectado. A lista cresce com
     * evidência, não com palpite: cada item novo devia vir com a linha real que
     * o justifica.
     */
    private static final List<String> MOVIMENTO_DE_INVESTIMENTO = List.of(
            "aplicacao", "resgate", "tesouro direto", "td", "conta global de inv",
            "cdb", "rdb", "lci", "lca", "poupanca", "previdencia",
            "fundo de investimento", "renda fixa", "tesouro selic", "vgbl", "pgbl");

    /**
     * O que devolve a linha para a soma mesmo falando de investimento.
     *
     * <p>Imposto, IOF e taxa são dinheiro que saiu de verdade. Rendimento,
     * juros, pontos e cashback são dinheiro que entrou de verdade. Nenhum dos
     * dois é o principal indo e voltando, e é a metade da regra que impede o
     * serviço de sumir com o imposto do dono.
     */
    private static final List<String> CUSTO_OU_PREMIO = List.of(
            "iof", "imposto", "taxa", "tarifa", "multa", "come cotas", "comecotas",
            "rendimento", "rendimentos", "juros", "pontos", "cashback");

    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;

    /**
     * Varre o histórico e marca o que é conta ↔ investimento do próprio dono.
     *
     * @param dryRun true = só relata. É como o dono confere antes de 350 linhas
     *               mudarem de lado de uma vez.
     */
    public Outcome sweep(String email, boolean dryRun) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
        List<BankTransaction> todas = bankTransactionRepository
                .findAllByUserIdOrderByDateDesc(user.getId());

        Set<UUID> marcar = new LinkedHashSet<>();
        List<Move> detalhes = new ArrayList<>();
        BigDecimal aplicado = BigDecimal.ZERO;
        BigDecimal resgatado = BigDecimal.ZERO;
        for (BankTransaction tx : todas) {
            // Já fora da soma por decisão do usuário ou por outra varredura:
            // não há o que fazer, e remarcar só inflaria o relatório
            if (tx.isInternalTransfer() || tx.isIgnored()) continue;
            if (!ehMovimentoDeInvestimento(tx.getDescription())) continue;
            marcar.add(tx.getId());
            BigDecimal valor = tx.getAmount();
            if (valor.signum() < 0) {
                aplicado = aplicado.add(valor.abs());
            } else {
                resgatado = resgatado.add(valor);
            }
            detalhes.add(new Move(tx.getId(), valor, tx.getDate().toLocalDate().toString(),
                    tx.getDescription()));
        }

        if (!dryRun && !marcar.isEmpty()) {
            bankTransactionRepository.markAsInternalTransfer(user.getId(), marcar);
        }
        log.info("Varredura de aplicação/resgate: {} de {} linha(s), aplicado {}, resgatado {}, "
                        + "dryRun={}, user={}",
                marcar.size(), todas.size(), aplicado, resgatado, dryRun, email);
        return new Outcome(todas.size(), marcar.size(), aplicado, resgatado, dryRun, detalhes);
    }

    /**
     * A linha é dinheiro indo ou voltando de um investimento do próprio dono?
     *
     * <p>Precisa das duas metades: falar de investimento <b>e</b> não ser custo
     * nem prêmio. Visível para o teste porque é aqui que mora a decisão inteira
     * — e é aqui que as pegadinhas do extrato real batem.
     */
    static boolean ehMovimentoDeInvestimento(String descricao) {
        String texto = CounterpartyMatcher.normalize(descricao);
        if (texto.isEmpty()) return false;
        String cercado = " " + texto + " ";
        if (CUSTO_OU_PREMIO.stream().anyMatch(marca -> cercado.contains(" " + marca + " "))) {
            return false;
        }
        return MOVIMENTO_DE_INVESTIMENTO.stream().anyMatch(marca -> cercado.contains(" " + marca + " "));
    }

    /** Uma linha marcada: quanto, quando e o que o extrato dizia. */
    public record Move(UUID transactionId, BigDecimal amount, String date, String description) {
    }

    /**
     * @param scanned   quantas linhas foram examinadas
     * @param marked    quantas saíram das somas de gasto e de renda
     * @param applied   soma do que saiu da conta rumo ao investimento
     * @param redeemed  soma do que voltou do investimento para a conta
     * @param dryRun    true = nada foi gravado
     * @param details   as linhas, para o dono conferir antes de confirmar
     */
    public record Outcome(int scanned, int marked, BigDecimal applied, BigDecimal redeemed,
                          boolean dryRun, List<Move> details) {
    }
}
