package br.com.economize.controller;

import br.com.economize.dto.wallet.TransactionRequest;
import br.com.economize.dto.wallet.TransactionResponse;
import br.com.economize.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallet")
@RequiredArgsConstructor
@Tag(name = "Wallet", description = "Gerenciamento da carteira de investimentos do usuário")
public class WalletController {

    private final WalletService walletService;

    @GetMapping("/transactions")
    @Operation(summary = "Listar operações da carteira",
            description = "Todas as compras e vendas do usuário, da mais recente para a mais antiga.")
    public Flux<TransactionResponse> getTransactions(@AuthenticationPrincipal String email) {
        return walletService.getUserTransactions(email);
    }

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar compra ou venda",
            description = "Grava uma operação na carteira. O tipo (BUY/SELL) é o que decide o sinal da posição.")
    public Mono<TransactionResponse> addTransaction(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody TransactionRequest request) {
        return walletService.addTransaction(email, request);
    }

    @DeleteMapping("/transactions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remover uma operação",
            description = "Apaga a operação do usuário. Id de outro usuário responde 404, nunca 403.")
    public Mono<Void> deleteTransaction(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id) {
        return walletService.deleteTransaction(email, id);
    }
}
