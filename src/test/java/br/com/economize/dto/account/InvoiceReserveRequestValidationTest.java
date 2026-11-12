package br.com.economize.dto.account;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O valor da reserva cabe na coluna NUMERIC(15,2). Sem o {@code @Digits}, um
 * valor com 3 casas ou com 14+ dígitos inteiros passava pela validação e
 * estourava no banco como 500 — tem de ser 400 com a frase em português.
 */
class InvoiceReserveRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private Set<ConstraintViolation<UpsertInvoiceReserveRequest>> validar(String valor) {
        return validator.validate(new UpsertInvoiceReserveRequest(new BigDecimal(valor), null, null));
    }

    @Test
    @DisplayName("valor no limite da coluna passa")
    void valorNoLimitePassa() {
        assertThat(validar("9999999999999.99")).isEmpty();
        assertThat(validar("320.57")).isEmpty();
    }

    @Test
    @DisplayName("mais de 2 casas ou mais de 13 dígitos inteiros é recusado")
    void valorForaDaColunaERecusado() {
        assertThat(validar("10.001"))
                .singleElement()
                .extracting(ConstraintViolation::getMessage)
                .asString()
                .contains("2 casas decimais");
        assertThat(validar("10000000000000.00")).hasSize(1);
    }
}
