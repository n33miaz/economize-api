package br.com.economize.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nenhum acento LITERAL no valor de uma propriedade.
 *
 * <p><b>O defeito, com data.</b> Em 16/09/2026 as notas da versão 2.3.3 saíram
 * em produção assim: <i>"O saldo agora vem do banco, e nÃ£o da soma do
 * extrato"</i>, <i>"CrÃ©dito dos cartÃµes"</i>, <i>"tendÃªncia"</i>. O arquivo
 * estava salvo em UTF-8 e o texto parecia certo em qualquer editor — mas o
 * Spring lê {@code .properties} como <b>ISO-8859-1</b>, então cada byte de uma
 * sequência UTF-8 vira um caractere diferente. O bug atravessou o build, os
 * testes e o deploy, e só apareceu na tela do usuário.
 *
 * <p><b>Por que um teste e não uma convenção.</b> O arquivo já avisava, em
 * comentário, que valor com acento tem de ir em {@code \\u00XX} — e a nota
 * seguinte foi escrita com acento literal de todo jeito. Comentário não
 * segura ninguém às onze da noite publicando uma versão; um teste, sim.
 *
 * <p>Os COMENTÁRIOS ficam livres: eles não são lidos como valor, e obrigá-los a
 * escapar acento tornaria o arquivo ilegível para quem mantém — o comentário
 * longo sobre memória da JVM tem mais acento do que código.
 */
class PropertiesEncodingTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    @DisplayName("valor de propriedade não carrega acento literal — o Spring lê o arquivo como ISO-8859-1")
    void nenhumAcenteLiteralEmValor() throws IOException {
        List<String> suspeitos = new ArrayList<>();

        try (var arquivos = Files.walk(RESOURCES)) {
            for (Path arquivo : arquivos
                    .filter(p -> p.getFileName().toString().endsWith(".properties"))
                    .toList()) {
                List<String> linhas = Files.readAllLines(arquivo, StandardCharsets.UTF_8);
                for (int i = 0; i < linhas.size(); i++) {
                    String linha = linhas.get(i);
                    String cru = linha.strip();
                    // Comentário e linha em branco não são valor
                    if (cru.isEmpty() || cru.startsWith("#") || cru.startsWith("!")) continue;
                    int igual = linha.indexOf('=');
                    if (igual < 0) continue;

                    String valor = linha.substring(igual + 1);
                    if (valor.chars().anyMatch(c -> c > 127)) {
                        suspeitos.add("%s:%d -> %s".formatted(
                                arquivo.getFileName(), i + 1,
                                valor.length() > 90 ? valor.substring(0, 90) + "…" : valor));
                    }
                }
            }
        }

        assertThat(suspeitos)
                .as("Valor com acento literal chega torto ao app (o Spring lê .properties como "
                        + "ISO-8859-1). Escreva o acento como \\u00XX — por exemplo \\u00e3 para 'ã'. "
                        + "Comentários podem ter acento à vontade.")
                .isEmpty();
    }
}
