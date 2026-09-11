package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.ConnectorAccountRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Quando as duas fontes discordam, quem manda é o aviso — não o número bonito.
 *
 * <p><b>O defeito, visto na casa do concorrente</b> em 09/09/2026: a tela de
 * saldos do Pierre mostrava <b>R$ 0,00</b> com o carimbo "Atualizado agora",
 * enquanto a tela de Conexões do mesmo app, no mesmo minuto, dizia que a última
 * leitura tinha 11 horas. Extrato com movimento e saldo zero não é saldo: é
 * leitura que falhou. O app não sabia, porque não tinha com quem conferir.
 *
 * <p><b>Nós tínhamos o mesmo buraco por outro caminho.</b> Todo saldo nosso
 * nasce da soma dos lançamentos importados. Falta um lançamento, o número fica
 * errado, e nada no sistema discorda — não havia segunda fonte. O EC-196
 * guardou o saldo que a instituição informa (V30) e este serviço é quem o
 * confronta.
 *
 * <p><b>O que este serviço NÃO faz</b>, e é a decisão mais importante dele:
 * não afirma que a soma dos lançamentos deveria bater com o saldo do banco.
 * Não deveria. O provedor devolve uns doze meses de histórico e a conta é mais
 * velha do que isso; a diferença entre as duas somas é, quase toda, dinheiro
 * que existia antes do primeiro lançamento que conhecemos. Gritar por causa
 * dela seria um alarme que toca sempre — que é o mesmo que alarme nenhum.
 *
 * <p>Os quatro avisos abaixo são só os que se pode <b>provar</b> com o que
 * temos. Cada um responde "o que exatamente está errado", nunca "os números não
 * batem".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceReconciliationService {

    /**
     * A partir de quando um saldo lido é velho demais para aparecer sem
     * ressalva. Dois dias: a sincronização é diária, então um saldo com mais de
     * 48 h significa que pelo menos uma leitura falhou em silêncio.
     */
    static final Duration SALDO_VELHO = Duration.ofHours(48);

    /**
     * Janela para decidir se a conta "tem movimento". Trinta dias porque conta
     * parada de verdade existe (a poupança que ninguém mexe), e chamar o saldo
     * zero dela de erro seria o alarme falso que tira a credibilidade do resto.
     */
    static final int DIAS_DE_MOVIMENTO = 30;

    private final ConnectorAccountRepository accountRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;

    public Report checkFor(String email) {
        return checkFor(email, OffsetDateTime.now());
    }

    /** @param agora injetado para o teste mandar no relógio. */
    public Report checkFor(String email, OffsetDateTime agora) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
        List<ConnectorAccount> contas = accountRepository
                .findAllByUserIdOrderByNameAsc(user.getId());
        List<BankTransaction> todas = bankTransactionRepository
                .findAllByUserIdOrderByDateDesc(user.getId());

        List<Finding> achados = new ArrayList<>();
        for (ConnectorAccount conta : contas) {
            // Origem desvinculada não sincroniza mais: cobrar saldo dela seria
            // cobrar de quem já avisou que parou
            if (conta.getPluggyItemId() == null) continue;
            achados.addAll(examinar(conta, todas, agora));
        }
        log.info("Conferência de saldo: {} conta(s) ligada(s), {} aviso(s), user={}",
                contas.stream().filter(c -> c.getPluggyItemId() != null).count(),
                achados.size(), email);
        return new Report(contas.size(), achados);
    }

    private List<Finding> examinar(ConnectorAccount conta, List<BankTransaction> todas,
                                   OffsetDateTime agora) {
        List<Finding> achados = new ArrayList<>();
        List<BankTransaction> daConta = todas.stream()
                .filter(tx -> conta.getId().equals(tx.getAccountId()))
                .toList();
        BigDecimal saldo = conta.getReportedBalance();
        OffsetDateTime lidoEm = conta.getReportedBalanceAt();

        if (saldo == null) {
            // Conta ligada que nunca informou saldo: ou o provedor não devolve,
            // ou a leitura falha toda vez. Nos dois casos o app está mostrando
            // um número sem contraprova, e quem lê tem direito de saber
            achados.add(new Finding(conta.getId(), conta.getName(), Kind.SEM_SALDO_INFORMADO,
                    null, null, null,
                    "A instituição não informou saldo nesta conta. O número da tela vem só "
                            + "dos lançamentos importados, sem segunda fonte para conferir."));
            return achados;
        }

        // Movimento com PISO (EC-212): um centavo de rendimento nos últimos
        // trinta dias não autoriza dizer que a leitura do saldo falhou — e o
        // aviso que aparece por centavo ensina o usuário a ignorar avisos
        boolean temMovimento = daConta.stream()
                .filter(tx -> tx.getDate().isAfter(agora.minusDays(DIAS_DE_MOVIMENTO)))
                .anyMatch(tx -> Materiality.vale(tx.getAmount()));
        if (saldo.signum() == 0 && temMovimento) {
            // O caso do concorrente, ao pé da letra
            achados.add(new Finding(conta.getId(), conta.getName(), Kind.ZERO_COM_MOVIMENTO,
                    saldo, lidoEm, null,
                    "A instituição informou saldo R$ 0,00, mas esta conta teve movimento nos "
                            + "últimos " + DIAS_DE_MOVIMENTO + " dias. Isso costuma ser leitura "
                            + "que falhou, não saldo."));
        }

        if (lidoEm != null && Duration.between(lidoEm, agora).compareTo(SALDO_VELHO) > 0) {
            long horas = Duration.between(lidoEm, agora).toHours();
            achados.add(new Finding(conta.getId(), conta.getName(), Kind.SALDO_VELHO,
                    saldo, lidoEm, null,
                    "O saldo desta conta foi lido há " + horas + " horas. A sincronização é "
                            + "diária, então pelo menos uma leitura falhou sem avisar."));
        }

        if (lidoEm != null) {
            // O único confronto exato que dá para fazer: o que entrou DEPOIS da
            // leitura ainda não está no saldo informado. Não é erro — é a
            // diferença entre o que o banco disse e o que já se sabe hoje, e
            // dizê-la é melhor do que mostrar um número e calar
            BigDecimal depois = daConta.stream()
                    .filter(tx -> tx.getDate().isAfter(lidoEm))
                    .filter(tx -> !tx.isIgnored())
                    .map(BankTransaction::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // O caso dos treze centavos do concorrente, ao pé da letra: um
            // movimento de R$ 0,13 depois da leitura é verdade e não é notícia
            if (Materiality.vale(depois)) {
                achados.add(new Finding(conta.getId(), conta.getName(), Kind.MOVIMENTO_APOS_LEITURA,
                        saldo, lidoEm, depois,
                        "Entraram lançamentos depois da última leitura de saldo. O saldo "
                                + "informado ainda não os inclui."));
            }
        }
        return achados;
    }

    /** O que exatamente está errado — nunca "os números não batem". */
    public enum Kind {
        /** A instituição nunca informou saldo: não há segunda fonte. */
        SEM_SALDO_INFORMADO,
        /** Saldo zero numa conta com movimento recente — o caso do concorrente. */
        ZERO_COM_MOVIMENTO,
        /** A última leitura é velha demais para a cadência de sincronização. */
        SALDO_VELHO,
        /** Há lançamentos posteriores à leitura; o saldo informado não os inclui. */
        MOVIMENTO_APOS_LEITURA
    }

    /**
     * @param reportedBalance  o que a instituição disse; nulo quando não disse
     * @param reportedAt       quando ela disse
     * @param movementAfter    soma do que entrou depois disso; nulo quando não
     *                         se aplica ao tipo de aviso
     */
    public record Finding(UUID accountId, String accountName, Kind kind,
                          BigDecimal reportedBalance, OffsetDateTime reportedAt,
                          BigDecimal movementAfter, String message) {
    }

    public record Report(int accountsChecked, List<Finding> findings) {

        /** Nenhum aviso: as duas fontes contam a mesma história. */
        public boolean clean() {
            return findings.isEmpty();
        }
    }
}
