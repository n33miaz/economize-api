package br.com.economize.service;

import br.com.economize.dto.statement.UpdateTransactionAliasRequest;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Renomear transação (EC-094). Fica fora do {@link TransactionReviewService} de
 * propósito: aquele é o elo do aprendizado — toda escrita dele realimenta o
 * motor de categorização. Aqui a escrita é o oposto disso: puramente
 * apresentacional, sem regra aprendida, sem mexer em categoria, sem tocar em
 * {@code normalizedDescription}. Manter os dois separados é o que impede o
 * apelido de virar insumo de motor por descuido de uma edição futura.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionAliasService {

    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;

    /**
     * Grava (ou limpa) o apelido. Apelido nulo/em branco volta a transação ao
     * descritivo do banco — limpar é o desfazer natural da renomeação, e não
     * precisa de rota própria.
     */
    @Transactional
    public BankTransaction rename(String email, UUID transactionId, String alias) {
        User user = requireUser(email);
        // consulta amarrada ao dono, não findById + checagem depois: com o filtro
        // dentro da query não existe caminho em que um id alheio seja carregado
        BankTransaction transaction = bankTransactionRepository
                .findByIdAndUserId(transactionId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Transação não encontrada"));

        String sanitized = sanitize(alias);
        // defesa em profundidade: o @Size do DTO protege só a rota; qualquer
        // chamador futuro estouraria o VARCHAR(80) com um 500 de persistência
        if (sanitized != null && sanitized.length() > UpdateTransactionAliasRequest.MAX_LENGTH) {
            throw new IllegalArgumentException("Apelido deve ter no máximo 80 caracteres");
        }
        transaction.setDisplayAlias(sanitized);
        // description e normalizedDescription saem daqui exatamente como
        // entraram: são eles que sustentam regras, recorrência e dedupe
        bankTransactionRepository.save(transaction);

        log.info("Apelido de transação {}: tx={}, user={}",
                sanitized == null ? "removido" : "atualizado", transactionId, email);
        return transaction;
    }

    /**
     * Achata o apelido para uma linha visível. O Postgres recusa NUL dentro de
     * texto (a rota morreria com 500 — o H2 dos testes não reproduz), e
     * invisíveis como zero-width space criariam um apelido que o usuário não
     * consegue ver nem corrigir na tela. Controles e quebras viram espaço,
     * formatadores invisíveis somem, espaços consecutivos colapsam; sobrando
     * nada, o apelido é limpo — o mesmo efeito do payload em branco.
     */
    private static String sanitize(String alias) {
        if (alias == null) {
            return null;
        }
        StringBuilder visible = new StringBuilder(alias.length());
        alias.codePoints().forEach(cp -> {
            if (Character.getType(cp) == Character.FORMAT) {
                return;
            }
            if (Character.isISOControl(cp) || Character.isWhitespace(cp)
                    || Character.isSpaceChar(cp)) {
                visible.append(' ');
                return;
            }
            visible.appendCodePoint(cp);
        });
        String collapsed = visible.toString().replaceAll(" {2,}", " ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }
}
