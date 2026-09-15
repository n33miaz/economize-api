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

    public AppVersionService(ObjectProvider<BuildProperties> buildProperties,
                             @Value("${economize.app.latest-version:2.3.2}") String latestVersion,
                             @Value("${economize.app.min-version:2.3.1}") String minVersion,
                             @Value("${economize.app.download-url:https://economize-web.onrender.com/baixar}")
                             String downloadUrl,
                             @Value("${economize.app.apk-url:}") String apkUrl,
                             @Value("${economize.app.store-url:}") String storeUrl,
                             @Value("${economize.app.update-message:" + AppVersionFilter.DEFAULT_MESSAGE + "}")
                             String updateMessage) {
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
        // BuildProperties só existe quando o jar carrega o build-info gerado
        // pelo plugin (ver pom); em `mvn spring-boot:run` e nos testes de fatia
        // ele não está lá, e "dev" é mais honesto do que inventar um número
        BuildProperties build = buildProperties.getIfAvailable();
        this.apiVersion = build == null || build.getVersion() == null ? DEV_VERSION : build.getVersion();
        this.schemaVersion = resolveSchemaVersion(
                new PathMatchingResourcePatternResolver(AppVersionService.class.getClassLoader()));
        log.info("Versões anunciadas: app publicada={} mínima={} api={} schema={}",
                latestVersion, minVersion, apiVersion, schemaVersion);
    }

    public AppVersionResponse describe() {
        return new AppVersionResponse(minVersion, latestVersion, downloadUrl, apkUrl, storeUrl,
                updateMessage, apiVersion, schemaVersion);
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
