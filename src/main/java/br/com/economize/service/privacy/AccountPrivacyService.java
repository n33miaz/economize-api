package br.com.economize.service.privacy;

import br.com.economize.model.User;
import br.com.economize.repository.UserRepository;
import br.com.economize.security.SessionVersionService;
import br.com.economize.service.MfaService;
import br.com.economize.service.connector.pluggy.PluggyClient;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Exclusão da própria conta: confere quem pede, apaga numa transação e, depois
 * do commit, desfaz as conexões no Pluggy.
 *
 * <p><b>Senha, depois segundo fator.</b> Mesmo critério do "desligar 2FA": quem
 * está com o celular desbloqueado na mão tem a sessão aberta, mas não a senha.
 * O código vem depois porque conferi-lo GASTA o passo do TOTP (ou risca um
 * código de recuperação) — uma senha errada não pode queimar um código.
 *
 * <p><b>Pluggy fora da transação e sem poder falhar a exclusão.</b> Apagar o
 * item lá revoga o consentimento bancário; se o agregador estiver fora, a
 * conta some daqui do mesmo jeito (o item órfão expira lá por inatividade) —
 * a mesma regra do desvincular de uma conexão só.
 */
@Slf4j
@Service
public class AccountPrivacyService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MfaService mfaService;
    private final SessionVersionService sessionVersionService;
    private final AccountDataEraser eraser;
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final ObjectProvider<PluggyClient> pluggyClient;

    public AccountPrivacyService(UserRepository userRepository,
                                 PasswordEncoder passwordEncoder,
                                 MfaService mfaService,
                                 SessionVersionService sessionVersionService,
                                 AccountDataEraser eraser,
                                 NamedParameterJdbcTemplate jdbc,
                                 EntityManager entityManager,
                                 PlatformTransactionManager transactionManager,
                                 ObjectProvider<PluggyClient> pluggyClient) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mfaService = mfaService;
        this.sessionVersionService = sessionVersionService;
        this.eraser = eraser;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.transaction = new TransactionTemplate(transactionManager);
        this.pluggyClient = pluggyClient;
    }

    public void deleteAccount(String email, String password, String code) {
        List<String> pluggyItemIds = transaction.execute(status -> {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
            if (password == null || !passwordEncoder.matches(password, user.getPassword())) {
                throw new IllegalArgumentException("Senha incorreta");
            }
            // antes do código: a recusa da casa não pode gastar um código do autenticador
            eraser.requireNoHouseholdToOrphan(user.getId());
            if (mfaService.isEnabledFor(user)) {
                if (code == null || code.isBlank()) {
                    throw new IllegalArgumentException("Informe o código do segundo fator");
                }
                if (!mfaService.verify(user, code.trim())) {
                    throw new IllegalArgumentException("Código do segundo fator inválido");
                }
            }
            List<String> itemIds = jdbc.queryForList(
                    "select item_id from pluggy_items where user_id = :uid",
                    new MapSqlParameterSource("uid", user.getId()), String.class);
            // descarta o cache de versão da sessão depois do commit: sem isso o
            // token seguiria valendo até 30 s depois da conta sumir
            sessionVersionService.revokeAll(user);
            // explícito, mesmo com o revokeAll já descartando: a exclusão não
            // pode depender de um efeito colateral para o token deixar de valer
            sessionVersionService.forget(user.getEmail());
            // o que o JPA tem pendente (o código de recuperação riscado, a
            // versão da sessão) vai ao banco ANTES do SQL apagar as linhas; e o
            // contexto esquece as entidades, para o commit não tentar regravar
            // linha que não existe mais
            entityManager.flush();
            entityManager.clear();
            eraser.eraseAll(user.getId());
            return itemIds;
        });
        revokeAtPluggy(pluggyItemIds);
    }

    private void revokeAtPluggy(List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) return;
        PluggyClient client = pluggyClient.getIfAvailable();
        if (client == null || !client.isConfigured()) return;
        try {
            String apiKey = client.authenticate();
            for (String itemId : itemIds) {
                try {
                    client.deleteItem(apiKey, itemId);
                } catch (Exception e) {
                    // sem id e sem corpo no log: identificador de terceiro e possível credencial
                    log.warn("Conta excluída, mas o Pluggy não confirmou a remoção de um item ({})",
                            e.getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            log.warn("Conta excluída, mas não foi possível falar com o Pluggy ({})", e.getClass().getSimpleName());
        }
    }
}
