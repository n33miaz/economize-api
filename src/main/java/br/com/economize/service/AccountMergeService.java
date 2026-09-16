package br.com.economize.service;

import br.com.economize.dto.account.AccountMergeSuggestion;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.InvoiceReserve;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.ConnectorAccountRepository;
import br.com.economize.repository.InvoiceReserveRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Juntar duas origens que são a mesma conta do mundo real.
 *
 * <p><b>O defeito, medido na conta do dono em 16/09/2026.</b> Ele disse que
 * "os números parecem estar meio embaralhados". A conta do Inter existia duas
 * vezes: uma origem <i>solta</i>, criada pelos arquivos que ele importou, com
 * <b>1.632 dos 1.967 lançamentos</b> e nenhum saldo; e uma origem <i>ligada</i>,
 * trazida pelo conector, com 75 lançamentos e o saldo de R$ 250,00. O Mercado
 * Pago tinha três origens; o Nubank, duas. Toda tela que agrupa por origem
 * mostrava o mesmo banco repetido, com números diferentes — e o saldo só era
 * conhecido justamente na origem que quase não tinha história.
 *
 * <p><b>Por que a adoção automática não pegou.</b>
 * {@code ConnectorAccountService.adoptable} exige nome E instituição iguais, e
 * é estreita de propósito: adotar a conta errada mistura o histórico de dois
 * cartões, erro pior do que a duplicata. Nos pares dele nada batia —
 * "Inter ····2750" contra "BANCO INTER ····2750", instituição "Inter" contra
 * "MeuPluggy". Afrouxar aquela regra trocaria um erro visível por um
 * silencioso; então a decisão sobe para quem sabe a resposta.
 *
 * <p><b>O sinal da sugestão são os últimos dígitos.</b> Todo rótulo de conta
 * carrega os quatro dígitos finais, e é o que distingue dois cartões do mesmo
 * banco. Dois rótulos com os MESMOS quatro dígitos e o MESMO tipo são
 * candidatos fortes — e ainda assim só candidatos: o app mostra os dois nomes,
 * quantos lançamentos cada um tem, e pergunta.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountMergeService {

    /**
     * Os últimos dígitos do rótulo.
     *
     * <p>Quatro é o padrão do mercado ("····2750"), mas o Pluggy às vezes
     * devolve três, e a conta criada à mão leva o que a pessoa digitou. Três é
     * o piso: com dois, "····50" casaria contas diferentes do mesmo banco, que
     * é exatamente o erro que não se pode cometer aqui.
     */
    private static final Pattern DIGITOS_FINAIS = Pattern.compile("(\\d{3,})\\D*$");

    private final ConnectorAccountRepository accountRepository;
    private final BankTransactionRepository transactionRepository;
    private final InvoiceReserveRepository reserveRepository;
    private final UserRepository userRepository;

    /**
     * Os pares que parecem a mesma conta, do mais volumoso para o menos — quem
     * tem mais lançamentos fora de lugar é quem mais embaralha os números.
     *
     * <p>Um par exige: mesmos dígitos finais, mesmo tipo, uma solta e uma
     * ligada. Duas soltas com os mesmos dígitos não entram: sem o conector
     * para dizer qual é a viva, não há como escolher quem fica.
     */
    public List<AccountMergeSuggestion> suggestionsFor(String email) {
        User user = requireUser(email);
        List<ConnectorAccount> contas = accountRepository.findAllByUserIdOrderByNameAsc(user.getId());

        List<AccountMergeSuggestion> sugestoes = new ArrayList<>();
        for (ConnectorAccount solta : contas) {
            if (solta.getPluggyItemId() != null) continue;
            String digitos = digitsOf(solta.getName());
            if (digitos == null) continue;

            List<ConnectorAccount> ligadas = contas.stream()
                    .filter(outra -> outra.getPluggyItemId() != null)
                    .filter(outra -> outra.getType() == solta.getType())
                    .filter(outra -> digitos.equals(digitsOf(outra.getName())))
                    .toList();
            // Duas ligadas com os mesmos dígitos é ambiguidade de verdade: não
            // sugerir é a resposta certa, porque adivinhar aqui mistura extrato
            if (ligadas.size() != 1) continue;

            ConnectorAccount ligada = ligadas.get(0);
            sugestoes.add(new AccountMergeSuggestion(
                    digitos,
                    solta.getId(), solta.getName(), solta.getInstitution(),
                    transactionRepository.countByUserIdAndAccountId(user.getId(), solta.getId()),
                    ligada.getId(), ligada.getName(), ligada.getInstitution(),
                    transactionRepository.countByUserIdAndAccountId(user.getId(), ligada.getId())));
        }
        sugestoes.sort(Comparator.comparingLong(AccountMergeSuggestion::sourceTransactions).reversed());
        log.info("Fusão de origens: {} par(es) sugerido(s), user={}", sugestoes.size(), user.getId());
        return sugestoes;
    }

    /**
     * Move tudo de {@code sourceId} para {@code targetId} e apaga a origem que
     * sobrou vazia.
     *
     * <p><b>A ordem importa e é obrigatória.</b> A chave estrangeira de
     * `bank_transactions.account_id` é {@code ON DELETE SET NULL} e a de
     * `invoice_reserves.card_account_id` é {@code ON DELETE CASCADE}. Apagar a
     * origem antes de mover transformaria os lançamentos em lançamentos sem
     * origem e <b>apagaria as reservas de fatura</b> — perda silenciosa de
     * dado que o usuário digitou. Move primeiro, apaga depois.
     *
     * <p><b>O que NÃO é aceito</b>, e cada recusa evita um estrago diferente:
     * juntar uma conta com ela mesma (não faz nada e some com a origem);
     * tipos diferentes (jogaria compras de cartão dentro de uma conta
     * corrente); absorver a origem LIGADA (é ela que recebe sincronização e
     * saldo — apagá-la faria o conector recriá-la na próxima leitura, e a
     * duplicata voltaria).
     *
     * @return quantos lançamentos mudaram de origem
     */
    @Transactional
    public int merge(String email, UUID sourceId, UUID targetId) {
        User user = requireUser(email);
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("Uma conta não se junta a ela mesma");
        }
        ConnectorAccount origem = requireOwned(sourceId, user.getId());
        ConnectorAccount destino = requireOwned(targetId, user.getId());

        if (origem.getType() != destino.getType()) {
            throw new IllegalArgumentException(
                    "Só dá para juntar contas do mesmo tipo: uma é " + origem.getType()
                            + " e a outra é " + destino.getType());
        }
        if (origem.getPluggyItemId() != null) {
            throw new IllegalArgumentException(
                    "A conta que desaparece tem de ser a desvinculada — a ligada é quem "
                            + "continua sincronizando, e o conector a recriaria na próxima leitura");
        }

        int movidos = transactionRepository.moveAccount(user.getId(), sourceId, targetId);

        // As reservas de fatura são dado DIGITADO pelo usuário e a FK as apaga
        // em cascata. Quando as duas origens têm reserva para o mesmo ciclo, a
        // do destino fica: ela é a da conta que sobrevive
        List<InvoiceReserve> reservas = reserveRepository
                .findAllByUserIdAndCardAccountId(user.getId(), sourceId);
        int reservasMovidas = 0;
        for (InvoiceReserve reserva : reservas) {
            boolean jaExiste = reserveRepository
                    .findByUserIdAndCardAccountIdAndReference(user.getId(), targetId, reserva.getReference())
                    .isPresent();
            if (jaExiste) {
                reserveRepository.delete(reserva);
                continue;
            }
            reserva.setCardAccount(destino);
            reserveRepository.save(reserva);
            reservasMovidas++;
        }

        accountRepository.delete(origem);
        log.info("Origens fundidas: {} lançamentos e {} reserva(s) de '{}' para '{}', user={}",
                movidos, reservasMovidas, origem.getName(), destino.getName(), user.getId());
        return movidos;
    }

    /**
     * Os dígitos finais do rótulo, ou nulo quando não há.
     *
     * <p>Nome sem dígito nenhum ("Flash · Vale refeição") não entra em sugestão
     * — sem os dígitos não há como afirmar que duas contas do mesmo banco são a
     * mesma, e este método existe justamente para não precisar chutar.
     */
    static String digitsOf(String name) {
        if (name == null) return null;
        Matcher m = DIGITOS_FINAIS.matcher(name.trim().toLowerCase(Locale.ROOT));
        if (!m.find()) return null;
        String digitos = m.group(1);
        // Fica com os quatro últimos: um rótulo pode trazer o número inteiro
        // ("5555777788880777") e o outro só o fim ("····0777")
        return digitos.length() > 4 ? digitos.substring(digitos.length() - 4) : digitos;
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }

    private ConnectorAccount requireOwned(UUID accountId, UUID userId) {
        return accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Conta não encontrada"));
    }
}
