package br.com.economize.security;

import br.com.economize.model.User;
import br.com.economize.repository.TrustedDeviceRepository;
import br.com.economize.repository.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;

/**
 * Versão da sessão de cada conta: o que permite derrubar tokens já emitidos.
 *
 * <p><b>Por que um cache.</b> Antes o filtro de autenticação não ia ao banco:
 * assinatura e validade bastavam. Conferir a versão em toda requisição custaria
 * uma consulta por chamada da API; com o cache, custa uma por conta a cada
 * {@link #TTL}.
 *
 * <p><b>Atraso.</b> Na MESMA instância a revogação vale na hora: o
 * {@link #revokeAll} descarta a entrada do cache assim que a transação
 * confirma. Em OUTRA instância, o valor velho pode sobreviver até {@link #TTL}
 * (30 s). Hoje o Render roda uma instância só, então o atraso real é zero; com
 * mais de uma, um token revogado ainda vale por no máximo 30 s nas outras.
 */
@Slf4j
@Service
public class SessionVersionService {

    /** Teto do atraso entre instâncias; curto porque o custo é só uma consulta leve. */
    static final Duration TTL = Duration.ofSeconds(30);

    /** Mesmo teto do limite de requisições: memória limitada mesmo sob ataque de e-mails variados. */
    private static final int MAX_ENTRIES = 10_000;

    private final UserRepository userRepository;
    private final TrustedDeviceRepository deviceRepository;
    private final Cache<String, Integer> versions = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(TTL)
            .build();

    public SessionVersionService(UserRepository userRepository, TrustedDeviceRepository deviceRepository) {
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
    }

    /** Versão em cache, sem tocar no banco; {@code null} se não estiver lá. */
    public Integer cachedVersion(String email) {
        return versions.getIfPresent(email);
    }

    /**
     * Versão atual da conta, indo ao banco quando o cache não sabe. BLOQUEANTE:
     * no filtro reativo, só chamar fora do event loop. Vazio = conta não existe
     * (apagada depois de o token sair) — e token de conta inexistente não vale.
     * Ausência não é guardada: não há o que revogar nela, e guardar "não existe"
     * atrasaria o primeiro acesso de quem acabou de se cadastrar.
     */
    public Optional<Integer> currentVersion(String email) {
        Integer cached = versions.getIfPresent(email);
        if (cached != null) return Optional.of(cached);
        Optional<Integer> found = userRepository.findSessionVersionByEmail(email);
        found.ifPresent(v -> versions.put(email, v));
        return found;
    }

    /**
     * Derruba TODAS as sessões da conta: soma 1 na versão (UPDATE atômico) e
     * esquece os aparelhos confiáveis.
     *
     * <p><b>Aparelhos confiáveis apagados, e não vinculados à versão.</b> Quem
     * revoga sessões está dizendo "perdi o controle de algum lugar". Um aparelho
     * lembrado dispensa o segundo fator; se ele estiver na mão de outra pessoa,
     * mantê-lo seria deixar a porta do código aberta justamente para quem se
     * quis expulsar. Apagar reaproveita o que o "esquecer todos" já faz, sem
     * coluna nova; o custo é o dono digitar o código uma vez em cada aparelho.
     *
     * <p>Participa da transação de quem chama (troca e redefinição de senha), e
     * o cache só é descartado DEPOIS do commit: descartar antes deixaria uma
     * requisição concorrente reler o valor antigo e guardá-lo por mais 30 s.
     */
    @Transactional
    public void revokeAll(User user) {
        userRepository.incrementSessionVersion(user.getId());
        deviceRepository.deleteAllByUserId(user.getId());
        evictAfterCommit(user.getEmail());
        log.info("Sessões revogadas para o usuário {}", user.getId());
    }

    private void evictAfterCommit(String email) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    versions.invalidate(email);
                }
            });
        } else {
            versions.invalidate(email);
        }
    }
}
