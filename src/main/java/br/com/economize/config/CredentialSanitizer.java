package br.com.economize.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apara as credenciais que chegam por variável de ambiente antes de qualquer bean
 * nascer.
 *
 * <p>O motivo é operacional: valores colados em painel de PaaS chegam com espaço
 * no fim ou entre aspas sem que ninguém veja, e o banco devolve
 * "password authentication failed" — uma mensagem que manda procurar no lugar
 * errado, porque a credencial está certa e só o valor transportado está sujo.
 *
 * <p>Também imprime a impressão digital do que será usado para conectar (host,
 * usuário e o TAMANHO da senha, nunca a senha). Sem isso, diagnosticar exige uma
 * rodada de deploy a cada palpite.
 */
public class CredentialSanitizer implements EnvironmentPostProcessor {

    private static final List<String> KEYS = List.of(
            "DB_URL", "DB_USER", "DB_PASSWORD",
            "JWT_SECRET", "CORS_ALLOWED_ORIGINS",
            "GEMINI_API_KEY", "BRAPI_TOKEN",
            // EC-107: Base64 colado no painel com espaço ou quebra de linha no
            // fim não decodifica, e a aplicação recusaria subir dizendo que a
            // chave-mestra é inválida — pelo motivo errado
            "SECRET_ENCRYPTION_KEY");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> cleaned = new LinkedHashMap<>();
        List<String> touched = new ArrayList<>();

        for (String key : KEYS) {
            String raw = environment.getProperty(key);
            if (raw == null) continue;
            String clean = sanitize(raw);
            if (!clean.equals(raw)) {
                cleaned.put(key, clean);
                touched.add(key);
            }
        }

        if (!cleaned.isEmpty()) {
            environment.getPropertySources()
                    .addFirst(new MapPropertySource("economize-credenciais-aparadas", cleaned));
        }

        report(environment, touched);
    }

    /** Remove espaços das pontas e um par de aspas que tenha vindo junto na cópia. */
    static String sanitize(String raw) {
        String value = raw.trim();
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    // Logging ainda não está inicializado neste ponto do boot; stdout é o que o
    // painel do Render mostra de qualquer forma
    private void report(ConfigurableEnvironment environment, List<String> touched) {
        String url = environment.getProperty("DB_URL");
        String user = environment.getProperty("DB_USER");
        String password = environment.getProperty("DB_PASSWORD");
        if (url == null && user == null && password == null) return;

        System.out.printf(
                "[credenciais] banco=%s usuario=%s senha=%s%s%n",
                describe(url), describe(user),
                password == null ? "AUSENTE" : password.length() + " caracteres",
                touched.isEmpty() ? "" : " | aparadas: " + String.join(", ", touched));
    }

    private String describe(String value) {
        return value == null ? "AUSENTE" : value;
    }
}
