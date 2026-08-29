package br.com.economize.controller;

import br.com.economize.dto.user.ChangePasswordRequest;
import br.com.economize.dto.user.UpdateUserRequest;
import br.com.economize.dto.user.UserMeResponse;
import br.com.economize.model.User;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.PasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Usuário", description = "Dados do usuário autenticado")
public class UserController {

    private final UserRepository userRepository;
    private final PasswordService passwordService;

    @Operation(summary = "Dados do usuário autenticado")
    @GetMapping("/me")
    public Mono<UserMeResponse> me(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> UserMeResponse.from(requireUser(email)))
                .subscribeOn(Schedulers.boundedElastic());
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
