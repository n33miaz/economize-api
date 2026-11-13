package br.com.economize.controller;

import br.com.economize.dto.integration.IntegrationKeyRequests;
import br.com.economize.dto.integration.IntegrationKeyResponses;
import br.com.economize.service.integration.IntegrationKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

/**
 * A gestão das chaves de integração (V44).
 *
 * <p>Estas rotas entram com o JWT de sessão do dono, como o resto do app — e
 * só com ele: a chave de integração não autentica aqui, então uma chave vazada
 * não consegue criar outra nem revogar as demais.
 */
@RestController
@RequestMapping("/api/v1/integration-keys")
@RequiredArgsConstructor
@Tag(name = "Chaves de integração", description = "As chaves com que outro sistema do dono lê as rotas de integração: criar (o segredo aparece uma única vez), listar e revogar")
public class IntegrationKeyController {

    private final IntegrationKeyService service;

    // Os métodos têm nome próprio (e não "create"/"list") porque o springdoc
    // deriva deles o operationId: um segundo "list" renumeraria os
    // operationId das rotas que já existem no contrato versionado

    @Operation(summary = "Criar uma chave",
            description = "Devolve o segredo (`eco_...`) em `secret` — é a ÚNICA vez em que ele aparece: o "
                    + "servidor guarda só o hash, e não há como recuperá-lo depois. No máximo 5 chaves "
                    + "ativas por pessoa; a sexta responde 409.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<IntegrationKeyResponses.IntegrationKeyCreated> createIntegrationKey(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody IntegrationKeyRequests.CreateIntegrationKey request) {
        return Mono.fromCallable(() -> service.create(email, request.name()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Minhas chaves",
            description = "Da mais nova para a mais antiga, revogadas inclusive, sem o segredo: nome, começo "
                    + "do segredo (`prefix`), criação, último uso (aproximado — atualizado no máximo a cada "
                    + "cinco minutos) e revogação.")
    @GetMapping
    public Mono<List<IntegrationKeyResponses.IntegrationKeyItem>> listIntegrationKeys(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> service.list(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Revogar uma chave",
            description = "A chave deixa de autenticar na hora e não volta. Revogar de novo é inofensivo. "
                    + "Chave de outra pessoa responde 404.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> revokeIntegrationKey(@AuthenticationPrincipal String email, @PathVariable UUID id) {
        return Mono.fromRunnable(() -> service.revoke(email, id))
                .subscribeOn(Schedulers.boundedElastic()).then();
    }
}
