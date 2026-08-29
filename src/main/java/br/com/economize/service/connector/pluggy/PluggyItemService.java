package br.com.economize.service.connector.pluggy;

import br.com.economize.dto.connector.PluggyItemResponse;
import br.com.economize.exception.ResourceConflictException;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.PluggyItem;
import br.com.economize.model.User;
import br.com.economize.repository.ConnectorAccountRepository;
import br.com.economize.repository.PluggyItemRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Ciclo de vida das conexões (itens) do Pluggy por usuário — EC-106. O fluxo é
 * o "conectar clicando": a API emite um connect token, o app abre o widget
 * Pluggy Connect, o widget cria o item e o app registra o itemId aqui. Nenhum
 * segredo (apiKey, clientSecret, accessToken) é gravado nem logado.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "economize.pluggy.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PluggyItemService {

    private final PluggyClient pluggyClient;
    private final UserRepository userRepository;
    private final PluggyItemRepository pluggyItemRepository;
    private final ConnectorAccountRepository accountRepository;

    // Legado da fase "itens globais por env" (antes do EC-106): enquanto as
    // variáveis existirem, elas funcionam como SEMENTE para a conta do dono
    // declarado — ver seedFromEnv. Não são mais lidas na hora do sync.
    @Value("${economize.pluggy.item-ids}")
    private String itemIdsRaw;

    @Value("${economize.pluggy.owner-email:}")
    private String ownerEmail;

    /**
     * Connect token para o widget. Com itemId, o widget abre em modo
     * atualização — e só o dono do item pode pedir isso (item alheio ou
     * inexistente responde 404, nunca 403: o id de outro usuário não pode nem
     * confirmar que existe).
     */
    public String connectToken(String email, String itemId) {
        User user = requireUser(email);
        requireCredentials();
        String normalized = itemId == null || itemId.isBlank() ? null : itemId.trim();
        if (normalized != null) {
            pluggyItemRepository.findByItemIdAndUserId(normalized, user.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado"));
        }
        String apiKey = pluggyClient.authenticate();
        // clientUserId = UUID interno: carimba o dono no item sem mandar PII
        return pluggyClient.connectToken(apiKey, user.getId().toString(), normalized);
    }

    /**
     * Registra o item que o widget criou. Antes de gravar, confirma no Pluggy
     * que o item existe e que o clientUserId carimbado pelo connect token é
     * deste usuário — itemId de sessão alheia responde o mesmo 404 do
     * inexistente, de propósito, para não confirmar a existência de nada.
     */
    public PluggyItemResponse register(String email, String rawItemId) {
        User user = requireUser(email);
        requireCredentials();
        String itemId = rawItemId.trim();
        if (pluggyItemRepository.existsByItemId(itemId)) {
            // mesma mensagem para "já é meu" e "é de outro usuário": o conflito
            // não pode servir de oráculo sobre contas alheias
            throw new ResourceConflictException("Este item já está registrado");
        }
        String apiKey = pluggyClient.authenticate();
        Map<String, Object> item = pluggyClient.item(apiKey, itemId);
        if (item == null || !stampedForUser(item, user)) {
            throw new ResourceNotFoundException(
                    "Item não encontrado no Pluggy — conclua a conexão no widget antes de registrar");
        }
        try {
            // saveAndFlush: a violação de unique tem que estourar AQUI para
            // virar 409. Deixada para o flush do commit, ela aconteceria fora do
            // alcance deste catch e o cliente veria 500 (mesmo padrão do EC-096)
            PluggyItem saved = pluggyItemRepository.saveAndFlush(fromPluggy(user, itemId, item));
            log.info("Item Pluggy registrado: conector \"{}\" para user={}", saved.getConnectorName(), email);
            return PluggyItemResponse.from(saved);
        } catch (DataIntegrityViolationException race) {
            // duplo toque no onSuccess do widget dispara dois POST /items com o
            // mesmo itemId: entre o existsByItemId acima e este insert, o outro
            // gravou. O unique de item_id é a garantia real — a checagem prévia
            // só poupa a ida ao Pluggy no caso comum. Mesma resposta do conflito
            // normal, para não distinguir corrida de duplicata.
            throw new ResourceConflictException("Este item já está registrado");
        }
    }

    /** Conexões do usuário autenticado — nunca devolve segredo algum. */
    public List<PluggyItemResponse> list(String email) {
        User user = requireUser(email);
        return pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()).stream()
                .map(PluggyItemResponse::from)
                .toList();
    }

    /**
     * Desvincula a conexão. O item também é apagado NO PLUGGY, em best-effort:
     * o item foi criado pela nossa aplicação, e deixá-lo órfão lá manteria o
     * consentimento bancário vivo sem ninguém consumindo. Se o Pluggy falhar, o
     * desvínculo local vale mesmo assim — a conta do usuário não fica refém da
     * disponibilidade do agregador (o item órfão expira lá por inatividade).
     */
    public void unlink(String email, java.util.UUID id) {
        User user = requireUser(email);
        PluggyItem item = pluggyItemRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado"));
        // as contas trazidas por esta conexão soltam o vínculo, mas CONTINUAM
        // existindo (EC-113): desconectar o banco não pode apagar a origem dos
        // lançamentos já importados dele — o histórico ainda precisa saber dizer
        // "isto foi no cartão do Nubank". O schema também declara ON DELETE SET
        // NULL; fazer aqui explicitamente é o que torna o comportamento testável
        accountRepository.detachFromItem(user.getId(), item.getId());
        pluggyItemRepository.delete(item);
        try {
            String apiKey = pluggyClient.authenticate();
            pluggyClient.deleteItem(apiKey, item.getItemId());
        } catch (Exception e) {
            // sem stack e sem corpo de resposta no log: pode carregar credencial
            log.warn("Desvinculado localmente, mas o Pluggy não confirmou a remoção do item ({})",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Migra os itens globais de env (PLUGGY_ITEM_IDS) para a tabela, uma única
     * vez, e apenas para a conta do PLUGGY_OWNER_EMAIL — exatamente quem já era
     * o único autorizado a sincronizá-los. Cada item é validado no Pluggy para
     * capturar o nome do conector; item que sumiu de lá é pulado. Enquanto a
     * env existir ela continua semeando: desvincular de vez um item semeado
     * exige removê-lo de PLUGGY_ITEM_IDS.
     *
     * <p>Este é o ÚNICO caminho que dispensa o carimbo clientUserId exigido em
     * {@link #register}, e a diferença é de origem, não de rigor: aqui os ids
     * vêm de uma allowlist de ambiente que só o operador do deploy edita, para
     * a conta que ele mesmo declarou — nada vem de requisição de usuário. São
     * justamente os itens criados no dashboard do Pluggy antes de o widget
     * existir, que nunca terão carimbo algum.
     */
    public void seedFromEnv(User user, String apiKey) {
        if (!isEnvOwner(user.getEmail())) return;
        for (String itemId : envItemIds()) {
            if (pluggyItemRepository.existsByItemId(itemId)) continue;
            Map<String, Object> item = pluggyClient.item(apiKey, itemId);
            if (item == null) {
                log.warn("Item de PLUGGY_ITEM_IDS não existe mais no Pluggy — ignorado na semente");
                continue;
            }
            try {
                PluggyItem saved = pluggyItemRepository.saveAndFlush(fromPluggy(user, itemId, item));
                log.info("Item de env semeado para user={}: conector \"{}\"",
                        user.getEmail(), saved.getConnectorName());
            } catch (DataIntegrityViolationException race) {
                // duas syncs simultâneas do dono semeiam o mesmo id: quem perde
                // a corrida apenas segue em frente. A semente é idempotente e
                // nunca pode derrubar a sincronização inteira por causa disso.
                log.info("Item de env já semeado por uma sincronização concorrente — seguindo");
            }
        }
    }

    /** Itens de env ainda não migrados — entram no itemCount do /status do dono. */
    public long pendingEnvItems(String email) {
        if (!isEnvOwner(email)) return 0;
        return envItemIds().stream()
                .filter(itemId -> !pluggyItemRepository.existsByItemId(itemId))
                .count();
    }

    public boolean hasEnvSeed(String email) {
        return isEnvOwner(email) && !envItemIds().isEmpty();
    }

    private PluggyItem fromPluggy(User user, String itemId, Map<String, Object> item) {
        Long connectorId = null;
        String connectorName = null;
        if (item.get("connector") instanceof Map<?, ?> connector) {
            if (connector.get("id") instanceof Number number) connectorId = number.longValue();
            Object name = connector.get("name");
            if (name != null) connectorName = truncate(String.valueOf(name), 160);
        }
        return PluggyItem.builder()
                .user(user)
                .itemId(itemId)
                .connectorId(connectorId)
                .connectorName(connectorName)
                .build();
    }

    /**
     * O item está carimbado para ESTE usuário? O carimbo é o
     * {@code options.clientUserId} que o nosso /connect-token grava ao abrir o
     * widget, com o UUID interno da conta.
     *
     * <p>Falha FECHADA de propósito: carimbo ausente RECUSA o registro. Item
     * criado fora do nosso widget (dashboard do Pluggy, outra integração, id
     * herdado da fase de itens globais) não tem dono comprovável, e aprová-lo
     * entregaria o extrato bancário de quem o criou a qualquer conta que
     * descubra o itemId. Se algum dia o Pluggy parar de devolver o campo no
     * GET /items/{id}, o registro nega TUDO e a feature quebra barulhenta — o
     * WARN abaixo é o que torna isso diagnosticável em produção — em vez de
     * passar a aceitar item alheio em silêncio.
     */
    private boolean stampedForUser(Map<String, Object> item, User user) {
        Object clientUserId = item.get("clientUserId");
        if (clientUserId == null || String.valueOf(clientUserId).isBlank()) {
            log.warn("Item sem clientUserId — registro negado. O item não foi criado pelo widget desta "
                    + "aplicação, ou a resposta do Pluggy deixou de trazer o campo. user={}", user.getEmail());
            return false;
        }
        return String.valueOf(clientUserId).equals(user.getId().toString());
    }

    private void requireCredentials() {
        if (!pluggyClient.isConfigured()) {
            throw new IllegalArgumentException(
                    "Conector Pluggy sem credenciais — defina PLUGGY_CLIENT_ID e PLUGGY_CLIENT_SECRET");
        }
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }

    private boolean isEnvOwner(String email) {
        return ownerEmail != null && !ownerEmail.isBlank()
                && ownerEmail.trim().equalsIgnoreCase(email);
    }

    private List<String> envItemIds() {
        if (itemIdsRaw == null || itemIdsRaw.isBlank()) return List.of();
        return Arrays.stream(itemIdsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
