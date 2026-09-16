package br.com.economize.service;

import br.com.economize.dto.app.AppVersionResponse;
import br.com.economize.security.AppVersionFilter;
import br.com.economize.security.SemanticVersion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Versões anunciadas ao app: a mínima aceita, a mais recente, e a identidade
 * da API e do banco que estão no ar.
 *
 * <p><b>São dois números, e antes eram um.</b> A versão publicada e a mínima
 * aceita eram a MESMA propriedade, e o argumento para isso estava escrito
 * aqui: dois números que podem divergir são dois números que um dia divergem,
 * e o modo de falha é silencioso — aparelho velho continua chamando a API e
 * recebendo dado que não sabe ler.
 *
 * <p>O argumento estava certo sobre o risco e errado sobre a cura. Juntar os
 * dois tornava <b>impossível publicar sem bloquear</b>: cada release trancava
 * todo mundo que ainda não tinha atualizado, e o dono não conseguia nem
 * anunciar uma versão nova sem barrar a anterior. Publicar e barrar são
 * eventos diferentes — a mínima sobe quando o <b>contrato</b> quebra (uma
 * migration que muda o que a API devolve), não quando sai versão.
 *
 * <p><b>E a divergência que machuca fica proibida no boot.</b> Mínima MAIOR
 * que a publicada é o único jeito de divergir que faz mal: manda a pessoa
 * buscar o que não existe e a tranca fora do app. O construtor recusa esse
 * estado. O contrário — mínima ATRÁS da publicada — é exatamente o que
 * permite anunciar sem trancar, e é o estado normal.
 *
 * <p><b>Ordem do deploy:</b> publicar o APK PRIMEIRO, subir a mínima DEPOIS.
 * Nunca o contrário.
 *
 * <p>A versão do schema não é uma constante escrita à mão — seria o primeiro
 * número a ficar para trás na próxima migration. Ela é lida dos nomes dos
 * arquivos {@code db/migration/V*.sql} na subida, exatamente o que o Flyway
 * aplica; a maior é o que este banco tem que ter para a API funcionar. É este
 * o vínculo "versão do app ↔ versão do banco" pedido pelo dono: a mínima do
 * app sobe quando uma migration muda o contrato, e o app pode mostrar as duas
 * lado a lado na tela "Sobre".
 */
@Slf4j
@Service
public class AppVersionService {

    static final String MIGRATIONS_PATTERN = "classpath*:db/migration/V*.sql";
    static final String UNKNOWN_SCHEMA = "unknown";
    static final String DEV_VERSION = "dev";

    // V23__users_plan_and_app_version.sql -> 23
    private static final Pattern MIGRATION_NAME = Pattern.compile("^V(\\d+)__.*\\.sql$");

    /**
     * Teto das notas da versão: a resposta é pública, cacheada e lida a cada
     * abertura do app — um changelog inteiro colado na variável de ambiente
     * viraria peso em toda abertura, e a folha de anúncio é uma folha, não um
     * documento. Dez frases curtas é o que cabe nela sem rolar duas telas.
     */
    static final int MAX_NOTES = 10;
    static final int MAX_NOTE_LENGTH = 200;
    static final String NOTE_ELLIPSIS = "…";

    /**
     * Um item por linha OU separados por barra vertical: a barra existe porque
     * o painel do Render aceita mal quebra de linha numa variável de ambiente,
     * e a quebra existe porque um .env local lê melhor com uma nota por linha.
     */
    private static final Pattern NOTE_SEPARATOR = Pattern.compile("\\||\\R");

    /** A publicada: quem está atrás vê o aviso e segue usando o app. */
    private final String latestVersion;

    /** A mínima aceita: abaixo dela o filtro responde 426. */
    private final String minVersion;
    private final String downloadUrl;
    private final String apkUrl;
    private final String storeUrl;
    private final String updateMessage;
    private final String apiVersion;
    private final String schemaVersion;
    /** O que há de novo na publicada, já aparado; vazia quando não há. */
    private final List<String> releaseNotes;

    public AppVersionService(ObjectProvider<BuildProperties> buildProperties,
                             @Value("${economize.app.latest-version:2.3.2}") String latestVersion,
                             @Value("${economize.app.min-version:2.3.1}") String minVersion,
                             @Value("${economize.app.download-url:https://economize-web.onrender.com/baixar}")
                             String downloadUrl,
                             @Value("${economize.app.apk-url:}") String apkUrl,
                             @Value("${economize.app.store-url:}") String storeUrl,
                             @Value("${economize.app.update-message:" + AppVersionFilter.DEFAULT_MESSAGE + "}")
                             String updateMessage,
                             @Value("${economize.app.release-notes:}") String releaseNotes) {
        this.latestVersion = latestVersion;
        this.minVersion = minVersion;
        // A única divergência que machuca: mínima na frente da publicada manda
        // a pessoa buscar o que não existe. Recusar no boot é mais barato que
        // descobrir pelo usuário trancado fora do app
        SemanticVersion publicada = SemanticVersion.parse(latestVersion).orElseThrow(
                () -> new IllegalStateException(
                        "economize.app.latest-version não é MAJOR.MINOR.PATCH: " + latestVersion));
        SemanticVersion minima = SemanticVersion.parse(minVersion).orElseThrow(
                () -> new IllegalStateException(
                        "economize.app.min-version não é MAJOR.MINOR.PATCH: " + minVersion));
        if (publicada.isOlderThan(minima)) {
            throw new IllegalStateException(
                    "economize.app.min-version (" + minVersion + ") é maior que a publicada ("
                            + latestVersion + "): isso tranca todo mundo e manda buscar uma "
                            + "versão que não existe");
        }
        this.downloadUrl = downloadUrl;
        // Vazio vira null, e não string vazia: é o estado "ainda não publiquei"
        // que a página /baixar sabe ler
        this.apkUrl = apkUrl == null || apkUrl.isBlank() ? null : apkUrl.trim();
        this.storeUrl = storeUrl == null || storeUrl.isBlank() ? null : storeUrl.trim();
        this.updateMessage = updateMessage;
        // As notas acompanham a PUBLICADA: quem as escreve é quem faz o bump
        // de latest-version, na mesma hora, e o texto vive na mesma variável
        // de ambiente do Render. Um release sem notas é lista vazia, não erro
        this.releaseNotes = parseReleaseNotes(releaseNotes);
        // BuildProperties só existe quando o jar carrega o build-info gerado
        // pelo plugin (ver pom); em `mvn spring-boot:run` e nos testes de fatia
        // ele não está lá, e "dev" é mais honesto do que inventar um número
        BuildProperties build = buildProperties.getIfAvailable();
        this.apiVersion = build == null || build.getVersion() == null ? DEV_VERSION : build.getVersion();
        this.schemaVersion = resolveSchemaVersion(
                new PathMatchingResourcePatternResolver(AppVersionService.class.getClassLoader()));
        log.info("Versões anunciadas: app publicada={} mínima={} api={} schema={} notas={}",
                latestVersion, minVersion, apiVersion, schemaVersion, this.releaseNotes.size());
    }

    public AppVersionResponse describe() {
        return new AppVersionResponse(minVersion, latestVersion, downloadUrl, apkUrl, storeUrl,
                updateMessage, apiVersion, schemaVersion, releaseNotes);
    }

    /**
     * Da variável de ambiente para a lista que o app mostra.
     *
     * <p>Quebra por barra vertical ou por linha, apara cada item e descarta os
     * vazios — {@code "A | | B"} são duas notas, não três. Depois aplica o teto:
     * do décimo primeiro item em diante nada sai, e um item mais longo que o
     * limite é cortado com reticências em vez de descartado, porque uma nota
     * grande demais ainda é uma nota, e sumir com ela em silêncio esconderia
     * do operador que o texto passou da medida.
     *
     * <p>Nulo ou em branco vira lista vazia, nunca nulo: o app soma
     * {@code notes.length} sem se defender, e o contrato aditivo promete isso.
     */
    static List<String> parseReleaseNotes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return NOTE_SEPARATOR.splitAsStream(raw)
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .limit(MAX_NOTES)
                .map(AppVersionService::trimNote)
                .toList();
    }

    private static String trimNote(String note) {
        if (note.length() <= MAX_NOTE_LENGTH) {
            return note;
        }
        // O corte deixa espaço para a reticência, assim o item devolvido nunca
        // passa do teto — é o teto que a documentação promete
        return note.substring(0, MAX_NOTE_LENGTH - NOTE_ELLIPSIS.length()).stripTrailing() + NOTE_ELLIPSIS;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public String apiVersion() {
        return apiVersion;
    }

    /**
     * Maior migration presente no classpath, pelo NÚMERO: a ordem alfabética
     * colocaria V9 depois de V22, e o Flyway também não ordena por texto.
     */
    static String resolveSchemaVersion(ResourcePatternResolver resolver) {
        try {
            Resource[] resources = resolver.getResources(MIGRATIONS_PATTERN);
            return highestMigration(Arrays.stream(resources).map(Resource::getFilename));
        } catch (IOException e) {
            // não derruba a subida: a versão do schema é informação, e a
            // aplicação em si continua igual sem ela
            log.warn("Não foi possível listar as migrations para anunciar a versão do schema ({})",
                    e.getClass().getSimpleName());
            return UNKNOWN_SCHEMA;
        }
    }

    static String highestMigration(Stream<String> filenames) {
        Optional<Integer> highest = filenames
                .filter(Objects::nonNull)
                .map(MIGRATION_NAME::matcher)
                .filter(Matcher::matches)
                .map(matcher -> Integer.parseInt(matcher.group(1)))
                .max(Comparator.naturalOrder());
        return highest.map(number -> "V" + number).orElse(UNKNOWN_SCHEMA);
    }
}
