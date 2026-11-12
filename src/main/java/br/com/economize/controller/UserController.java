package br.com.economize.controller;

import br.com.economize.config.ClientIpResolver;
import br.com.economize.dto.user.ChangePasswordRequest;
import br.com.economize.dto.user.ConnectionInfoResponse;
import br.com.economize.dto.user.UpdateUserRequest;
import br.com.economize.dto.user.UserMeResponse;
import br.com.economize.dto.user.UserStatsResponse;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.ReportRepository;
import br.com.economize.repository.TransactionRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.PasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Usuário", description = "Dados do usuário autenticado")
public class UserController {

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    // Só para contar: o hub do Perfil mostra três números, e antes disso a tela
    // baixava as três listas inteiras para chegar a eles
    private final BankTransactionRepository bankTransactionRepository;
    private final TransactionRepository transactionRepository;
    private final ReportRepository reportRepository;

    @Operation(summary = "Dados do usuário autenticado")
    @GetMapping("/me")
    public Mono<UserMeResponse> me(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> UserMeResponse.from(requireUser(email)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Contadores do hub do Perfil",
            description = "Só os números de extrato, carteira e relatórios. O Perfil desenhava os três "
                    + "contadores baixando as três listas inteiras (100 KB só de extrato para 1.752 linhas) "
                    + "a cada abertura, quando o que aparecia na tela era a contagem.")
    @GetMapping("/me/stats")
    public Mono<UserStatsResponse> stats(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> {
            UUID userId = requireUser(email).getId();
            return new UserStatsResponse(
                    bankTransactionRepository.countByUserId(userId),
                    transactionRepository.countByUserId(userId),
                    reportRepository.countByUserId(userId));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // Diagnóstico: conferir o IP enxergado depois de qualquer mudança de
    // hospedagem. O limite de login é por IP, e quem decide qual salto é o
    // cliente é o ClientIpResolver; esta rota mostra a cadeia real que chegou
    // e o que ele escolheu, para não adivinhar. Só cabeçalhos de
    // encaminhamento são lidos: nenhum outro (Authorization, Cookie) sai daqui
    @Operation(summary = "Como a API enxergou a conexão desta requisição",
            description = "Diagnóstico, só leitura: saltos do X-Forwarded-For na ordem e classificados "
                    + "(interno, Cloudflare, público), CF-Connecting-IP, "
                    + "True-Client-IP, X-Real-IP, Forwarded, o endereço remoto (que pode vir reescrito a "
                    + "partir do X-Forwarded-For, e não ser o soquete) e o IP que o resolvedor "
                    + "escolheu. Serve para conferir o IP enxergado depois de mudança de hospedagem.")
    @GetMapping("/me/connection")
    public Mono<ConnectionInfoResponse> connection(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        // mesma junção do resolvedor: várias linhas do cabeçalho formam uma cadeia só
        // a classificação vem do próprio resolvedor, para o diagnóstico nunca discordar dele
        List<ConnectionInfoResponse.Hop> hops = Arrays
                .stream(String.join(",", headers.getOrEmpty("X-Forwarded-For")).split(","))
                .map(String::trim)
                .filter(hop -> !hop.isEmpty())
                .map(hop -> new ConnectionInfoResponse.Hop(hop, ClientIpResolver.classify(hop)))
                .toList();
        // não é necessariamente o soquete: no Render chega reescrito para a
        // ponta esquerda do X-Forwarded-For (ver ClientIpResolver)
        InetSocketAddress remote = request.getRemoteAddress();
        InetAddress socket = remote == null ? null : remote.getAddress();
        String remoteAddress = socket == null ? null : socket.getHostAddress();
        boolean rewritten = remoteAddress != null && !hops.isEmpty()
                && remoteAddress.equals(ClientIpResolver.normalize(hops.get(0).address()));
        return Mono.just(new ConnectionInfoResponse(
                hops,
                headers.getFirst("CF-Connecting-IP"),
                headers.getFirst("True-Client-IP"),
                headers.getFirst("X-Real-IP"),
                headers.getOrEmpty("Forwarded"),
                remoteAddress,
                socket == null ? null : ClientIpResolver.classify(socket),
                rewritten,
                ClientIpResolver.resolve(request)));
    }

    @Operation(summary = "Atualizar nome do usuário")
    @PatchMapping("/me")
    public Mono<UserMeResponse> update(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody UpdateUserRequest request) {
        return Mono.fromCallable(() -> {
            User user = requireUser(email);
            user.setName(request.name().trim());
            return UserMeResponse.from(userRepository.save(user));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(operationId = "changePassword", summary = "Alterar senha do usuário autenticado",
            description = "Exige a senha atual; se não conferir, retorna 400.")
    @PostMapping("/me/change-password")
    public Mono<ResponseEntity<Void>> changePassword(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody ChangePasswordRequest request) {
        return Mono.fromCallable(() -> {
            passwordService.changePassword(email, request.currentPassword(), request.newPassword());
            return ResponseEntity.noContent().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }
}
