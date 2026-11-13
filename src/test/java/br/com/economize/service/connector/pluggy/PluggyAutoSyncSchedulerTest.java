package br.com.economize.service.connector.pluggy;

import br.com.economize.exception.ResourceConflictException;
import br.com.economize.exception.ServiceUnavailableException;
import br.com.economize.model.User;
import br.com.economize.repository.PluggyItemRepository;
import br.com.economize.service.BankStatementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluggyAutoSyncSchedulerTest {

    @Mock
    private PluggySyncService syncService;
    @Mock
    private PluggyItemRepository pluggyItemRepository;

    private PluggyAutoSyncScheduler scheduler(boolean enabled) {
        return new PluggyAutoSyncScheduler(syncService, pluggyItemRepository, enabled, 90);
    }

    private static User user(String email) {
        return User.builder().id(UUID.randomUUID()).email(email).name("x").password("x").build();
    }

    private static PluggySyncService.SyncResult ok(int imported) {
        return new PluggySyncService.SyncResult(new BankStatementService.ImportResult(
                UUID.randomUUID(), imported, 0, 0, 0, false, "PLUGGY", List.of()), 1);
    }

    @Test
    @DisplayName("chama a mesma sync do botao, com a janela padrao do app, para cada usuario, em ordem")
    void sincronizaCadaUsuarioComItemAtivo() throws Exception {
        User a = user("a@x.com");
        User b = user("b@x.com");
        when(pluggyItemRepository.findUsersWithReadableItems()).thenReturn(List.of(a, b));
        when(syncService.sync(anyString(), anyInt(), anyBoolean())).thenReturn(ok(3));

        PluggyAutoSyncScheduler.Summary s = scheduler(true).runOnce();

        InOrder ordem = inOrder(syncService);
        ordem.verify(syncService).sync("a@x.com", 90, true);
        ordem.verify(syncService).sync("b@x.com", 90, true);
        assertEquals(new PluggyAutoSyncScheduler.Summary(2, 2, 0, 0), s);
    }

    @Test
    @DisplayName("sem usuario com conexao legivel (sem item ou so itens mortos) nao chama a sync")
    void semItemAtivoNaoSincroniza() {
        // a consulta e quem filtra NOT_FOUND / NEEDS_USER_ACTION e quem nao tem item
        when(pluggyItemRepository.findUsersWithReadableItems()).thenReturn(List.of());

        PluggyAutoSyncScheduler.Summary s = scheduler(true).runOnce();

        verifyNoInteractions(syncService);
        assertEquals(new PluggyAutoSyncScheduler.Summary(0, 0, 0, 0), s);
    }

    @Test
    @DisplayName("usuario ja sincronizando pelo botao e pulado e os outros seguem")
    void pulaQuemJaEstaSincronizando() {
        User a = user("a@x.com");
        User b = user("b@x.com");
        when(pluggyItemRepository.findUsersWithReadableItems()).thenReturn(List.of(a, b));
        when(syncService.sync("a@x.com", 90, true)).thenThrow(new ResourceConflictException("em andamento"));
        when(syncService.sync("b@x.com", 90, true)).thenReturn(ok(1));

        PluggyAutoSyncScheduler.Summary s = scheduler(true).runOnce();

        verify(syncService).sync("b@x.com", 90, true);
        assertEquals(new PluggyAutoSyncScheduler.Summary(2, 1, 1, 0), s);
    }

    @Test
    @DisplayName("falha de um usuario nao interrompe os outros")
    void falhaDeUmNaoParaOsOutros() {
        User a = user("a@x.com");
        User b = user("b@x.com");
        User c = user("c@x.com");
        when(pluggyItemRepository.findUsersWithReadableItems()).thenReturn(List.of(a, b, c));
        when(syncService.sync("a@x.com", 90, true)).thenThrow(new ServiceUnavailableException("banco fora"));
        when(syncService.sync("b@x.com", 90, true)).thenThrow(new IllegalArgumentException("nada a ler"));
        when(syncService.sync("c@x.com", 90, true)).thenReturn(ok(2));

        PluggyAutoSyncScheduler.Summary s = scheduler(true).runOnce();

        verify(syncService).sync("c@x.com", 90, true);
        assertEquals(new PluggyAutoSyncScheduler.Summary(3, 1, 0, 2), s);
    }

    @Test
    @DisplayName("enabled=false nao consulta ninguem nem sincroniza")
    void desligadoNaoRoda() {
        PluggyAutoSyncScheduler.Summary s = scheduler(false).runOnce();

        verifyNoInteractions(pluggyItemRepository, syncService);
        assertEquals(new PluggyAutoSyncScheduler.Summary(0, 0, 0, 0), s);
    }

    @Test
    @DisplayName("erro ao listar usuarios nao escapa do metodo agendado")
    void erroNaConsultaNaoEscapa() {
        when(pluggyItemRepository.findUsersWithReadableItems()).thenThrow(new IllegalStateException("db"));

        scheduler(true).scheduledSync();

        verify(syncService, never()).sync(anyString(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("segunda passada so le: mesma sync, sem pedir coleta, para cada usuario")
    void segundaPassadaSoLe() {
        User a = user("a@x.com");
        User b = user("b@x.com");
        when(pluggyItemRepository.findUsersWithReadableItems()).thenReturn(List.of(a, b));
        when(syncService.sync("a@x.com", 90, false)).thenThrow(new ResourceConflictException("em andamento"));
        when(syncService.sync("b@x.com", 90, false)).thenReturn(ok(4));

        scheduler(true).scheduledRead();

        verify(syncService).sync("b@x.com", 90, false);
        verify(syncService, never()).sync(anyString(), anyInt(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    @DisplayName("segunda passada: falha de um nao para os outros, e enabled=false nao roda")
    void segundaPassadaProtecoes() {
        User a = user("a@x.com");
        User b = user("b@x.com");
        when(pluggyItemRepository.findUsersWithReadableItems()).thenReturn(List.of(a, b));
        when(syncService.sync("a@x.com", 90, false)).thenThrow(new ServiceUnavailableException("fora"));
        when(syncService.sync("b@x.com", 90, false)).thenReturn(ok(0));

        assertEquals(new PluggyAutoSyncScheduler.Summary(2, 1, 0, 1), scheduler(true).runOnce(false));

        org.mockito.Mockito.clearInvocations(pluggyItemRepository, syncService);
        scheduler(false).scheduledRead();
        verifyNoInteractions(pluggyItemRepository, syncService);
    }

    @Test
    @DisplayName("cron padrao 06:00 no fuso financeiro e bean so existe com o Pluggy ligado")
    void contratoDoAgendamento() throws Exception {
        Scheduled scheduled = PluggyAutoSyncScheduler.class.getMethod("scheduledSync").getAnnotation(Scheduled.class);
        assertEquals("${economize.pluggy.auto-sync.cron:0 0 6 * * *}", scheduled.cron());
        assertEquals("${economize.finance.zone:America/Sao_Paulo}", scheduled.zone());
        Scheduled leitura = PluggyAutoSyncScheduler.class.getMethod("scheduledRead").getAnnotation(Scheduled.class);
        assertEquals("${economize.pluggy.auto-sync.read-cron:0 0 7 * * *}", leitura.cron());
        assertEquals("${economize.finance.zone:America/Sao_Paulo}", leitura.zone());

        ConditionalOnProperty cond = PluggyAutoSyncScheduler.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("economize.pluggy.enabled", cond.name()[0]);
        assertEquals("true", cond.havingValue());
    }
}
