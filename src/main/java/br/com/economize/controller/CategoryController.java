package br.com.economize.controller;

import br.com.economize.dto.category.CategoryResponse;
import br.com.economize.dto.category.CreateCategoryRequest;
import br.com.economize.dto.category.UpdateCategoryRequest;
import br.com.economize.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
@Tag(name = "Categorias", description = "Categorias do sistema e do usuário para classificação de transações")
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "Listar categorias visíveis (sistema + do usuário)")
    @GetMapping
    public Mono<List<CategoryResponse>> list(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> categoryService.listVisible(email).stream()
                        .map(CategoryResponse::from)
                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Criar categoria personalizada")
    @PostMapping
    public Mono<ResponseEntity<CategoryResponse>> create(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody CreateCategoryRequest request) {
        return Mono.fromCallable(() -> categoryService.create(email, request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(category -> ResponseEntity.status(HttpStatus.CREATED).body(CategoryResponse.from(category)));
    }

    @Operation(summary = "Editar categoria do usuário (parcial)")
    @PatchMapping("/{id}")
    public Mono<CategoryResponse> update(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCategoryRequest request) {
        return Mono.fromCallable(() -> categoryService.update(email, id, request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(CategoryResponse::from);
    }

    @Operation(summary = "Excluir categoria do usuário",
            description = "Exclui se não houver referências; caso contrário arquiva para preservar o histórico.")
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> delete(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id) {
        return Mono.fromCallable(() -> categoryService.deleteOrArchive(email, id))
                .subscribeOn(Schedulers.boundedElastic())
                .map(deleted -> ResponseEntity.ok(Map.of(
                        "deleted", deleted,
                        "archived", !deleted)));
    }
}
