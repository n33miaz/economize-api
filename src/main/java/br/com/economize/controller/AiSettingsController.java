package br.com.economize.controller;

import br.com.economize.dto.ai.AiKeyTestRequest;
import br.com.economize.dto.ai.AiKeyTestResponse;
import br.com.economize.dto.ai.AiProviderCatalogResponse;
import br.com.economize.dto.ai.AiSettingsResponse;
import br.com.economize.dto.ai.SaveAiSettingsRequest;
import br.com.economize.service.ai.UserAiSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
@Tag(name = "Opções de IA", description = "EC-107 — o usuário escolhe provedor, modelo e traz a própria "
        + "chave (BYOK). A chave é cifrada em repouso e NUNCA volta em resposta alguma. Quem não "
        + "cadastrar nada continua usando a chave do servidor, exatamente como antes.")
public class AiSettingsController {

    private final UserAiSettingsService service;

    @Operation(summary = "Catálogo de provedores e modelos",
            description = "Lista fixa do servidor, sobrescrevível por configuração de ambiente. O app "
                    + "monta o seletor com isto e não fala com provedor nenhum para descobrir modelos. "
                    + "Quando byokAvailable é falso, esta instalação não tem cofre e o cadastro "
                    + "responderia 503 — esconda a opção.")
    @GetMapping("/providers")
    public Mono<AiProviderCatalogResponse> providers() {
        return Mono.fromCallable(service::catalog).subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Configuração de IA em vigor",
            description = "Sem chave própria cadastrada responde source=SERVER com o provedor e o modelo "
                    + "do servidor. Com chave própria responde source=USER — e ainda assim SEM a chave: "
                    + "no máximo os 4 últimos caracteres, para o usuário reconhecer qual cadastrou. "
                    + "keyStatus=UNREADABLE avisa que a chave existe mas não pode mais ser lida.")
    @GetMapping("/settings")
    public Mono<AiSettingsResponse> settings(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> service.current(email)).subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Cadastrar ou trocar a chave própria",
            description = "Substitui a configuração anterior por inteiro — a chave antiga é descartada, "
                    + "não arquivada. A chave é cifrada em AES-256-GCM antes de tocar o banco e não é "
                    + "validada contra o provedor aqui (use /settings/test).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Salvo; a resposta é a mesma do GET"),
            @ApiResponse(responseCode = "400", description = "Provedor ou modelo fora do catálogo, ou chave "
                    + "em formato impossível", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "503", description = "Instalação sem chave-mestra de criptografia",
                    content = @io.swagger.v3.oas.annotations.media.Content)
    })
    @PutMapping("/settings")
    public Mono<AiSettingsResponse> save(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody SaveAiSettingsRequest request) {
        return Mono.fromCallable(() -> service.save(email, request)).subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Remover a chave própria",
            description = "A conta volta a usar a chave do servidor. Sem configuração cadastrada responde "
                    + "404 — inclusive quando a configuração existe, mas é de outra conta: a de outro "
                    + "usuário não é endereçável por esta rota.")
    @DeleteMapping("/settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@AuthenticationPrincipal String email) {
        return Mono.fromRunnable(() -> service.delete(email))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Operation(summary = "Testar a chave contra o provedor",
            description = "Faz UMA chamada mínima ao provedor. Com corpo, testa a chave enviada sem "
                    + "gravá-la (permite \"Testar\" antes de \"Salvar\"); sem corpo, testa a que já está "
                    + "cadastrada. Chave recusada responde 200 com ok=false e um motivo classificado — "
                    + "o teste rodou, a resposta é que foi não. O corpo de erro do provedor nunca é "
                    + "repassado.")
    @PostMapping("/settings/test")
    public Mono<AiKeyTestResponse> test(
            @AuthenticationPrincipal String email,
            @RequestBody(required = false) AiKeyTestRequest request) {
        return Mono.fromCallable(() -> service.test(email, request)).subscribeOn(Schedulers.boundedElastic());
    }
}
