package br.com.economize.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Qual commit está no ar — o que a conferência pós-deploy
 * ({@code .github/workflows/conferir-producao.yml}) espera aparecer no
 * {@code /actuator/info} para saber que a publicação entrou.
 *
 * <p>O Render injeta {@code RENDER_GIT_COMMIT} no ambiente de todo serviço, em
 * build e em execução, inclusive no runtime Docker. Fora dele (local, testes,
 * esteira) a variável não existe e o valor é {@code local}.
 *
 * <p><b>Por que um contribuidor próprio, e não o de ambiente.</b> Ligar
 * {@code management.info.env.enabled} publicaria TUDO que começa com
 * {@code info.} — inclusive uma variável {@code INFO_*} criada no painel do
 * Render, que o Spring traduz para {@code info.*}. O {@code /actuator/info} é
 * público; este contribuidor garante que dele só sai este campo.
 */
@Component
public class DeployInfoContributor implements InfoContributor {

    private final String commit;

    public DeployInfoContributor(@Value("${RENDER_GIT_COMMIT:local}") String commit) {
        // Vazio conta como ausente: um "" no /info pareceria deploy sem commit
        this.commit = commit == null || commit.isBlank() ? "local" : commit.trim();
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("deploy", Map.of("commit", commit));
    }
}
