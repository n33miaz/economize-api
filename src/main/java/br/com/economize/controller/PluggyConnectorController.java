package br.com.economize.controller;

import br.com.economize.dto.connector.ConnectTokenResponse;
import br.com.economize.dto.connector.PluggyItemResponse;
import br.com.economize.dto.connector.RegisterPluggyItemRequest;
import br.com.economize.service.connector.pluggy.PluggyItemService;
import br.com.economize.service.connector.pluggy.PluggySyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/connectors/pluggy")
@RequiredArgsConstructor
@Tag(name = "Conector Meu Pluggy", description = "Open Finance via Pluggy (ADR-011, EC-106) — atrás da flag PLUGGY_ENABLED; itens vinculados por usuário")
public class PluggyConnectorController {

    // presentes só com economize.pluggy.enabled=true
    private final ObjectProvider<PluggySyncService> syncService;
    private final ObjectProvider<PluggyItemService> itemService;

    @Operation(summary = "Estado do conector", description = "enabled/configured/itemCount do usuário autenticado — o app decide se mostra a opção. "
            + "O campo owner é legado (itens são por usuário desde o EC-106) e responde sempre true.")
    @GetMapping("/status")
    public Mono<Map<String, Object>> status(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> {
            PluggySyncService service = syncService.getIfAvailable();
            if (service == null) {
                // "owner" também aqui: o APK publicado lê os QUATRO campos, e
                // com a flag desligada ele sumia da resposta. Vale true pelo
                // mesmo motivo do caminho ligado — desde o EC-106 toda conta é
                // dona das próprias conexões; quem decide se a opção aparece é
                // "enabled"/"configured".
                return Map.<String, Object>of(
                        "enabled", false, "owner", true, "configured", false, "itemCount", 0);
            }
            return service.status(email);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Connect token para o widget Pluggy Connect",
            description = "Token de curta duração que o app usa para abrir o widget e o usuário conectar uma "
                    + "instituição clicando. Com ?itemId= (de um item já vinculado a esta conta), o widget abre "
                    + "em modo atualização da conexão (credencial expirada/MFA). O token não é a apiKey da "
                    + "aplicação e não dá acesso a dados de outros usuários.")
    @PostMapping("/connect-token")
    public Mono<ConnectTokenResponse> connectToken(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String itemId) {
        return Mono.fromCallable(() -> new ConnectTokenResponse(requireItems().connectToken(email, itemId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Registrar item criado pelo widget",
            description = "O app devolve o itemId que o Pluggy Connect entregou no onSuccess. A API confirma no "
                    + "Pluggy que o item existe e pertence a esta conta antes de gravar; item inexistente ou de "
                    + "outra sessão responde 404, itemId já registrado responde 409.")
    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PluggyItemResponse> registerItem(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody RegisterPluggyItemRequest request) {
        return Mono.fromCallable(() -> requireItems().register(email, request.itemId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Listar conexões do usuário",
            description = "Itens vinculados à conta autenticada, com instituição e carimbo da última "
                    + "sincronização. Nenhum segredo é exposto.")
    @GetMapping("/items")
    public Mono<List<PluggyItemResponse>> listItems(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> requireItems().list(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Desvincular conexão",
            description = "Remove o vínculo local e apaga o item no Pluggy (best-effort — revoga o consentimento "
                    + "no agregador). Item de outro usuário responde 404.")
    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unlinkItem(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id) {
        return Mono.fromRunnable(() -> requireItems().unlink(email, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Operation(summary = "Sincronizar contas conectadas",
            description = "Percorre os itens DO USUÁRIO autenticado (contas BANK e cartão de crédito) e puxa as "
                    + "transações da janela (default 90 dias) para o mesmo pipeline do upload: categorização, "
                    + "dedup por id e reconciliação entre fontes.")
    @PostMapping("/sync")
    public Mono<Map<String, Object>> sync(
            @AuthenticationPrincipal String email,
            @RequestParam(defaultValue = "90") int days) {
        return Mono.fromCallable(() -> {
            PluggySyncService service = syncService.getIfAvailable();
            if (service == null) {
                throw new IllegalArgumentException("Conector Pluggy desativado — defina PLUGGY_ENABLED=true");
            }
            var sync = service.sync(email, days);
            var result = sync.result();
            // contrato do APK publicado: os campos existentes ficam; só se soma
            Map<String, Object> body = new HashMap<>();
            body.put("uploadId", result.uploadId());
            body.put("transactionsImported", result.transactionsImported());
            body.put("suggested", result.suggested());
            body.put("uncategorized", result.uncategorized());
            body.put("reconciled", result.reconciled());
            body.put("format", result.format());
            body.put("itemsSynced", sync.itemsSynced());
            return body;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private PluggyItemService requireItems() {
        PluggyItemService service = itemService.getIfAvailable();
        if (service == null) {
            throw new IllegalArgumentException("Conector Pluggy desativado — defina PLUGGY_ENABLED=true");
        }
        return service;
    }
}
