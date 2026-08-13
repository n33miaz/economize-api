package br.com.economize.controller;

import br.com.economize.service.connector.pluggy.PluggySyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/connectors/pluggy")
@RequiredArgsConstructor
@Tag(name = "Conector Meu Pluggy", description = "Open Finance via Meu Pluggy (ADR-011) — atrás da flag PLUGGY_ENABLED")
public class PluggyConnectorController {

    // presente só com economize.pluggy.enabled=true
    private final ObjectProvider<PluggySyncService> syncService;

    @Operation(summary = "Estado do conector", description = "enabled/configured/itemCount — o app decide se mostra a opção.")
    @GetMapping("/status")
    public Mono<Map<String, Object>> status(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> {
            PluggySyncService service = syncService.getIfAvailable();
            if (service == null) {
                return Map.<String, Object>of("enabled", false, "configured", false, "itemCount", 0);
            }
            return service.status(email);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Sincronizar contas conectadas",
            description = "Puxa as transações da janela (default 90 dias) para o mesmo pipeline do upload: "
                    + "categorização, dedup por id e reconciliação entre fontes.")
    @PostMapping("/sync")
    public Mono<Map<String, Object>> sync(
            @AuthenticationPrincipal String email,
            @RequestParam(defaultValue = "90") int days) {
        return Mono.fromCallable(() -> {
            PluggySyncService service = syncService.getIfAvailable();
            if (service == null) {
                throw new IllegalArgumentException("Conector Pluggy desativado — defina PLUGGY_ENABLED=true");
            }
            var result = service.sync(email, days);
            Map<String, Object> body = new HashMap<>();
            body.put("uploadId", result.uploadId());
            body.put("transactionsImported", result.transactionsImported());
            body.put("suggested", result.suggested());
            body.put("uncategorized", result.uncategorized());
            body.put("reconciled", result.reconciled());
            body.put("format", result.format());
            return body;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
