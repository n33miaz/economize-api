package br.com.economize.dto.app;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * O que o app precisa saber sobre versões antes mesmo de fazer login.
 *
 * <p>{@code apiVersion} e {@code schemaVersion} são a resposta ao pedido de
 * alinhar app, API e banco: a primeira vem do build-info do jar em execução e
 * a segunda é a maior migration presente no classpath — a mesma que o Flyway
 * aplicou (ou vai aplicar) na subida. Quem olhar a tela "Sobre" do app e o
 * dashboard do Supabase enxerga o mesmo número.
 *
 * <p>{@code notes} é o que há de novo na versão publicada, uma frase por
 * item. Nasceu aditivo e nunca nulo: o app que ainda não sabe ler a lista a
 * ignora, e o que sabe não precisa tratar ausência — sem notas ele recebe a
 * lista vazia e simplesmente não mostra o título.
 */
public record AppVersionResponse(
        /** Abaixo desta o app é bloqueado (426) e mandado atualizar. */
        String minVersion,
        /** A mais recente publicada — o app pode sugerir atualizar sem bloquear. */
        String latestVersion,
        /** Página do site com o APK/instruções. */
        String downloadUrl,
        /**
         * O arquivo em si, quando publicado. Nulo enquanto não houver.
         *
         * <p>A página {@code /baixar} só mostra o botão de download quando
         * este campo vem preenchido — oferecer um link que dá 404 para quem
         * acabou de ser bloqueado seria pior do que dizer "em breve".
         */
        String apkUrl,
        /** Loja, quando houver listagem; nulo hoje. */
        String storeUrl,
        /** Texto da tela de atualização, escrito pelo operador. */
        String message,
        /** Versão da build da API ("dev" fora de um jar empacotado). */
        String apiVersion,
        /** Maior migration do classpath, ex.: "V23". */
        String schemaVersion,
        /**
         * Notas da versão publicada: o que mudou, uma linha por item, já
         * aparadas pelo servidor (no máximo dez itens de até duzentos
         * caracteres). Lista vazia quando o operador não escreveu nada.
         */
        @ArraySchema(
                arraySchema = @Schema(description = "O que há de novo na versão publicada, uma frase por "
                        + "item; vazia quando não há notas. No máximo 10 itens de até 200 caracteres."),
                schema = @Schema(example = "Mercado com mais moedas"))
        List<String> notes
) {
}
