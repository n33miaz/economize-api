package br.com.economize.service.connector.pluggy;

import br.com.economize.exception.ResourceConflictException;
import br.com.economize.model.User;
import br.com.economize.repository.PluggyItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sincronização automática do Open Finance, uma vez por dia.
 *
 * <p><b>Por que existe.</b> Até aqui a sincronização só rodava quando alguém
 * tocava em "Sincronizar". Quem não abre o app fica sem lançamento novo — a
 * conta do dono passou de setembro a novembro assim, sem erro nenhum.
 *
 * <p><b>O que faz.</b> Para cada usuário com ao menos uma conexão ainda
 * legível, chama a MESMA {@link PluggySyncService#sync} do botão, com a mesma
 * janela padrão do app (90 dias). Por isso herda tudo dela: a trava por
 * usuário, o pulo das conexões mortas, o pedido de coleta nova com o seu
 * espaçamento de 6 horas e a deduplicação.
 *
 * <p><b>Um usuário por vez.</b> O plano grátis tem 512 MB e uma fração de CPU;
 * cada sync segura a janela inteira em memória. Em série, o pico é o de uma
 * sync só. O método bloqueia a thread do agendador de propósito (o mesmo
 * padrão do {@code NewsRefreshScheduler}); o ciclo de notícias atrasa alguns
 * minutos nesse horário, o que é aceitável.
 *
 * <p><b>Coleta nova.</b> A sync pede ao Pluggy ({@code requestUpdate}) que leia
 * o banco de novo quando o retrato tem mais de 6 horas. A aplicação é de
 * desenvolvimento, sem a atualização automática do próprio Pluggy (só existe em
 * produção); sem esse pedido o retrato nunca muda. Uma vez por dia por conexão
 * fica muito abaixo do limite documentado (20 PATCH por minuto). A coleta é
 * assíncrona do lado deles: o que ela trouxer entra na sync seguinte (a do dia
 * seguinte ou a do botão).
 *
 * <p><b>Reinício perto do horário.</b> O Render reinicia a instância no deploy.
 * Não guardamos "já rodou hoje" (seria coluna nova ou tabela sem semântica
 * para isso); se o processo subir de novo antes do próximo disparo do cron, ele
 * só roda no próximo horário — o cron não recupera disparo perdido. A duplicata
 * rara (duas instâncias vivas no instante da troca do deploy) é inofensiva: a
 * importação deduplica pelo id do Pluggy e o pedido de coleta respeita as 6
 * horas entre pedidos.
 *
 * <p>Só existe com o conector ligado ({@code PLUGGY_ENABLED}); e mesmo ligado
 * pode ser desligado pelo painel do Render ({@code PLUGGY_AUTO_SYNC_ENABLED}).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "economize.pluggy.enabled", havingValue = "true")
public class PluggyAutoSyncScheduler {

    /** A janela padrão do botão no app ({@code syncConnector(days = 90)}). */
    static final int JANELA_PADRAO_DIAS = 90;

    private final PluggySyncService syncService;
    private final PluggyItemRepository pluggyItemRepository;
    private final boolean enabled;
    private final int days;

    public PluggyAutoSyncScheduler(PluggySyncService syncService, PluggyItemRepository pluggyItemRepository,
                                   @Value("${economize.pluggy.auto-sync.enabled:true}") boolean enabled,
                                   @Value("${economize.pluggy.auto-sync.days:" + JANELA_PADRAO_DIAS + "}") int days) {
        this.syncService = syncService;
        this.pluggyItemRepository = pluggyItemRepository;
        this.enabled = enabled;
        this.days = days;
    }

    @Scheduled(cron = "${economize.pluggy.auto-sync.cron:0 0 6 * * *}",
            zone = "${economize.finance.zone:America/Sao_Paulo}")
    public void scheduledSync() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            // nunca deixar a exceção subir: o agendador seguiria, mas o log
            // ficaria com a pilha e sem o resumo
            log.error("Sincronização automática abortada: {}", e.getClass().getSimpleName());
        }
    }

    /** Uma rodada completa. Devolve o resumo para os testes e para o log. */
    Summary runOnce() {
        if (!enabled) {
            log.info("Sincronização automática desligada (economize.pluggy.auto-sync.enabled=false).");
            return new Summary(0, 0, 0, 0);
        }
        List<User> users = pluggyItemRepository.findUsersWithReadableItems();
        log.info("Sincronização automática: {} usuário(s) com conexão ativa, janela de {} dias.",
                users.size(), days);
        int ok = 0;
        int busy = 0;
        int failed = 0;
        for (User user : users) {
            // id curto basta para achar a linha; e-mail e banco são dados da pessoa
            String quem = idCurto(user);
            try {
                PluggySyncService.SyncResult result = syncService.sync(user.getEmail(), days);
                ok++;
                log.info("Auto-sync {}: {} conexão(ões) lida(s), {} pulada(s), {} lançamento(s) novo(s).",
                        quem, result.itemsSynced(), result.skippedConnections().size(),
                        result.result() == null ? 0 : result.result().transactionsImported());
            } catch (ResourceConflictException emAndamento) {
                // a pessoa está sincronizando pelo botão agora: a dela já basta
                busy++;
                log.info("Auto-sync {}: já havia sincronização em andamento; pulado.", quem);
            } catch (RuntimeException e) {
                // falha de um usuário não pode parar os outros. Sem a mensagem:
                // ela pode trazer o nome do banco
                failed++;
                log.warn("Auto-sync {}: falhou ({}).", quem, e.getClass().getSimpleName());
            }
        }
        Summary summary = new Summary(users.size(), ok, busy, failed);
        log.info("Sincronização automática concluída: {} ok, {} em andamento, {} com falha, de {}.",
                ok, busy, failed, users.size());
        return summary;
    }

    private static String idCurto(User user) {
        String id = String.valueOf(user.getId());
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    /** Contagens de uma rodada. */
    record Summary(int users, int synced, int skippedBusy, int failed) {
    }
}
