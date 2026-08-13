package br.com.economize.dto.statement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ReviewApplyRequest(
        @NotEmpty @Valid List<Item> items
) {
    // learnPattern null = true: aprender é o comportamento esperado do motor
    public record Item(
            @NotEmpty List<UUID> transactionIds,
            @NotNull UUID categoryId,
            Boolean learnPattern
    ) {
    }
}
