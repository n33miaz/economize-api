package br.com.painel_economico.controller;

import br.com.painel_economico.model.BankTransaction;
import br.com.painel_economico.service.BankStatementService;
import br.com.painel_economico.service.statement.parser.StatementFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/bank-statements")
@RequiredArgsConstructor
@Tag(name = "Extratos Bancários", description = "Importação multi-formato (OFX, CSV, XLSX, PDF, TXT) com idempotência por hash")
public class BankStatementController {

    private final BankStatementService bankStatementService;

    @Operation(summary = "Upload de extrato bancário",
            description = "Aceita OFX, CSV, XLSX, PDF e TXT. Idempotente por hash SHA-256.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> upload(
            @AuthenticationPrincipal String email,
            @RequestPart("file") Mono<FilePart> filePartMono) {

        return filePartMono
                .flatMap(filePart -> {
                    try {
                        StatementFormat.fromFilename(filePart.filename());
                    } catch (IllegalArgumentException ex) {
                        return Mono.error(ex);
                    }
                    return bankStatementService.processFile(email, filePart);
                })
                .map(result -> ResponseEntity.ok(Map.of(
                        "message", result.duplicated()
                                ? "Arquivo já importado anteriormente."
                                : "Arquivo processado com sucesso.",
                        "transactionsImported", result.transactionsImported(),
                        "format", result.format(),
                        "duplicated", result.duplicated())));
    }

    @Operation(summary = "Listar transações bancárias")
    @GetMapping
    public Flux<BankTransaction> list(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> bankStatementService.listTransactions(email))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapIterable(list -> list);
    }
}
