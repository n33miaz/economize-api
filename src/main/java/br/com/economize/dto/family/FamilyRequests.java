package br.com.economize.dto.family;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Os corpos de entrada do grupo familiar (EC-149).
 *
 * <p>Juntos no mesmo arquivo, como em {@code WishRequests}: são quatro records
 * pequenos que se leem melhor lado a lado. {@code shareScope} chega como texto
 * e é validado no serviço — enum no record derrubaria a desserialização com
 * 500 em vez de responder 400 dizendo quais valores existem.
 */
public final class FamilyRequests {

    private FamilyRequests() {
    }

    /** Criar a casa. Sem nome, ela se chama "Casa". */
    public record CreateFamily(
            @Size(max = 60, message = "Nome deve ter no máximo 60 caracteres")
            String name
    ) {
    }

    public record UpdateFamily(
            @NotBlank(message = "Nome da casa é obrigatório")
            @Size(max = 60, message = "Nome deve ter no máximo 60 caracteres")
            String name
    ) {
    }

    /**
     * Entrar com o código. Maiúsculas, espaços e hífens são normalizados no
     * serviço: o código é trocado por mensagem e digitado à mão.
     */
    public record JoinFamily(
            @NotBlank(message = "Código do convite é obrigatório")
            @Size(max = 32, message = "Código inválido")
            String code
    ) {
    }

    /**
     * O que EU compartilho com a casa. PUT: substitui os quatro campos de uma
     * vez — é o estado inteiro da tela "O que eu compartilho". Listas nulas
     * valem como vazias; {@code includeUnassigned} nulo vale como verdadeiro,
     * que é o padrão do modelo.
     */
    public record UpdateSharing(
            @NotBlank(message = "Escopo de compartilhamento é obrigatório")
            String shareScope,

            List<UUID> hiddenCategoryIds,

            List<UUID> sharedAccountIds,

            Boolean includeUnassigned
    ) {
    }
}
