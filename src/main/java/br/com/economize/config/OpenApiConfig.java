package br.com.economize.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A capa da documentação da API.
 *
 * <p><b>Sem declaração de licença, e é deliberado.</b> O campo dizia
 * {@code MIT} — uma licença que diz, em texto, que qualquer um pode usar,
 * copiar e vender este código. Não existe arquivo LICENSE em nenhum dos dois
 * repositórios, e o dono decidiu em 15/09/2026 que não quer essa declaração:
 * <i>"tire tudo do código e das documentações que fala que o app é a fim de
 * estudos e que qualquer um pode usar o código"</i>. Um campo de licença que
 * ninguém escolheu é pior do que campo nenhum, porque ele é a única coisa que
 * um leitor vai tomar como decisão consciente.
 *
 * <p>Sem licença declarada, vale o padrão da lei: direitos reservados.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Economize! API")
                        .version("v1")
                        .description("BFF do app Economize! — finanças pessoais, parsers multi-formato, relatórios e assistente IA.")
                        .contact(new Contact().name("Neemias Manso").email("neemias.manso@jcgestaoderiscos.com.br")))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
