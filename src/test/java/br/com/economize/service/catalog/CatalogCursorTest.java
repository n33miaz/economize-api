package br.com.economize.service.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogCursorTest {

    private static final String HASH = "a1b2c3d4e5";

    @Test
    @DisplayName("Cursor deve sobreviver ao ciclo codifica/decodifica")
    void shouldRoundTrip() {
        CatalogCursor original = new CatalogCursor(1234L, 42, "stock_PETR4", HASH);

        CatalogCursor decoded = CatalogCursor.decode(original.encode(), HASH);

        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("Id com o separador dentro não pode corromper a decodificação")
    void shouldPreserveSeparatorInsideId() {
        CatalogCursor original = new CatalogCursor(7L, 3, "stock_A|B", HASH);

        assertEquals("stock_A|B", CatalogCursor.decode(original.encode(), HASH).lastId());
    }

    @Test
    @DisplayName("Cursor que não é base64 deve virar erro de requisição inválida")
    void shouldRejectGarbage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CatalogCursor.decode("isso!!!nao@eh#cursor", HASH));

        assertTrue(error.getMessage().startsWith("Cursor inválido"));
    }

    @Test
    @DisplayName("Cursor com número de campos errado deve ser rejeitado")
    void shouldRejectWrongShape() {
        String raw = encodeRaw("1|10|stock_X");

        assertThrows(IllegalArgumentException.class, () -> CatalogCursor.decode(raw, HASH));
    }

    @Test
    @DisplayName("Cursor de versão desconhecida deve ser rejeitado")
    void shouldRejectUnknownVersion() {
        String raw = encodeRaw("9|10|3|" + HASH + "|stock_X");

        assertThrows(IllegalArgumentException.class, () -> CatalogCursor.decode(raw, HASH));
    }

    @Test
    @DisplayName("Posição não numérica deve ser rejeitada")
    void shouldRejectNonNumericPosition() {
        String raw = encodeRaw("1|dez|3|" + HASH + "|stock_X");

        assertThrows(IllegalArgumentException.class, () -> CatalogCursor.decode(raw, HASH));
    }

    @Test
    @DisplayName("Posição negativa deve ser rejeitada")
    void shouldRejectNegativePosition() {
        String raw = encodeRaw("1|10|-5|" + HASH + "|stock_X");

        assertThrows(IllegalArgumentException.class, () -> CatalogCursor.decode(raw, HASH));
    }

    @Test
    @DisplayName("Cursor de outra combinação de filtros deve ser rejeitado")
    void shouldRejectCursorFromAnotherFilterSet() {
        String cursor = new CatalogCursor(1L, 0, "stock_X", HASH).encode();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CatalogCursor.decode(cursor, "outrohash1"));

        assertTrue(error.getMessage().contains("outra combinação de filtros"));
    }

    private String encodeRaw(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
