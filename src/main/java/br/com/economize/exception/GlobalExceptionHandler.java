package br.com.economize.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

        @ExceptionHandler(IllegalArgumentException.class)
        public ProblemDetail handleIllegalArgumentException(IllegalArgumentException ex) {
                log.warn("Erro de validação: {}", ex.getMessage());
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
                problemDetail.setTitle("Requisição Inválida");
                problemDetail.setType(Objects
                                .requireNonNull(URI.create("https://economize.app/erros/requisicao-invalida")));
                problemDetail.setProperty("timestamp", Instant.now());
                return problemDetail;
        }

        // Falha de @Valid no WebFlux — sem este handler cairia no genérico como 500
        @ExceptionHandler(WebExchangeBindException.class)
        public ProblemDetail handleValidationException(WebExchangeBindException ex) {
                String detail = ex.getFieldErrors().stream()
                                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                                .collect(Collectors.joining("; "));
                log.warn("Erro de validação de payload: {}", detail);
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                                detail.isBlank() ? "Payload inválido" : detail);
                problemDetail.setTitle("Requisição Inválida");
                problemDetail.setType(Objects
                                .requireNonNull(URI.create("https://economize.app/erros/requisicao-invalida")));
                problemDetail.setProperty("timestamp", Instant.now());
                return problemDetail;
        }

        @ExceptionHandler(WebClientResponseException.class)
        public ProblemDetail handleWebClientResponseException(WebClientResponseException ex) {
                log.error("Erro na comunicação com API externa: Status={}, Body={}", ex.getStatusCode(),
                                ex.getResponseBodyAsString());
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                                "Falha ao obter dados de provedores externos.");
                problemDetail.setTitle("Erro no Provedor de Dados");
                problemDetail.setType(Objects
                                .requireNonNull(URI.create("https://economize.app/erros/provedor-externo")));
                problemDetail.setProperty("timestamp", Instant.now());
                return problemDetail;
        }

        @ExceptionHandler(Exception.class)
        public ProblemDetail handleGenericException(Exception ex) {
                log.error("Erro inesperado no servidor: ", ex);
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                                "Ocorreu um erro inesperado. Tente novamente mais tarde.");
                problemDetail.setTitle("Erro Interno do Servidor");
                problemDetail.setType(
                                Objects.requireNonNull(URI.create("https://economize.app/erros/erro-interno")));
                problemDetail.setProperty("timestamp", Instant.now());
                return problemDetail;
        }
}