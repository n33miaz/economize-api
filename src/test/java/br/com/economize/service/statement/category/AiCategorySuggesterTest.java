package br.com.economize.service.statement.category;

import br.com.economize.model.Category;
import br.com.economize.model.User;
import br.com.economize.service.ai.AiChatCaller;
import br.com.economize.service.ai.AiChatCallerFactory;
import br.com.economize.service.ai.AiProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * As DUAS PORTAS do bônus de sugestão de categoria (EC-107) e, acima de tudo, a
 * promessa que sustenta o ticket inteiro no caminho de importação: <b>nada aqui
 * pode derrubar o upload</b>. Chave ilegível, provedor fora do ar, resposta
 * malformada — o desfecho é sempre o mesmo mapa vazio, e o extrato entra.
 */
class AiCategorySuggesterTest {

    private static final List<String> DESCRICOES = List.of("fitmax", "posto ipiranga");

    private AiChatCallerFactory factory;
    private AiChatCaller caller;
    private User user;
    private List<Category> catalogo;

    @BeforeEach
    void setUp() {
        factory = mock(AiChatCallerFactory.class);
        caller = mock(AiChatCaller.class);
        user = User.builder().id(UUID.randomUUID()).email("dono@economize.app").build();
        catalogo = List.of(categoria("Academia", "academia"), categoria("Transporte", "transporte"));
    }

    private static Category categoria(String nome, String slug) {
        return Category.builder()
                .id(UUID.randomUUID())
                .name(nome)
                .slug(slug)
                .flow(Category.Flow.EXPENSE)
                .archived(false)
                .build();
    }

    private AiCategorySuggester suggester(boolean serverKeyAllowed) {
        return new AiCategorySuggester(factory, serverKeyAllowed);
    }

    // ------------------------------------------------------------------
    // As duas portas
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Flag do servidor DESLIGADA e sem chave própria: nenhuma IA é chamada e o mapa volta vazio")
    void shouldDoNothingWhenNoDoorIsOpen() {
        when(factory.resolve(eq(user), eq(false))).thenReturn(Optional.empty());

        assertThat(suggester(false).suggest(user, DESCRICOES, catalogo)).isEmpty();
        assertThat(suggester(false).appliesTo(user)).isFalse();
        verifyNoInteractions(caller);
    }

    @Test
    @DisplayName("Porta 1 — flag do servidor LIGADA: o fallback do servidor é permitido")
    void shouldAllowServerKeyWhenFlagIsOn() {
        when(factory.resolve(eq(user), eq(true))).thenReturn(Optional.of(caller));
        when(caller.complete(anyString(), anyString())).thenReturn("{\"fitmax\":\"academia\"}");

        Map<String, String> sugestoes = suggester(true).suggest(user, DESCRICOES, catalogo);

        assertThat(sugestoes).containsExactly(Map.entry("fitmax", "academia"));
        assertThat(suggester(true).appliesTo(user)).isTrue();
        // o segundo argumento é o que autoriza gastar a chave do DONO DO DEPLOY
        verify(factory, never()).resolve(eq(user), eq(false));
    }

    @Test
    @DisplayName("Porta 2 — flag DESLIGADA mas o usuário tem chave própria: a sugestão sai mesmo assim")
    void shouldRunOnUserKeyEvenWithServerFlagOff() {
        when(factory.resolve(eq(user), eq(false))).thenReturn(Optional.of(caller));
        when(caller.userOwned()).thenReturn(true);
        when(caller.complete(anyString(), anyString()))
                .thenReturn("{\"fitmax\":\"academia\",\"posto ipiranga\":\"transporte\"}");

        Map<String, String> sugestoes = suggester(false).suggest(user, DESCRICOES, catalogo);

        assertThat(sugestoes)
                .containsEntry("fitmax", "academia")
                .containsEntry("posto ipiranga", "transporte");
        // quem paga é o usuário, então o fallback do servidor NUNCA é autorizado
        // por esta porta
        verify(factory, never()).resolve(eq(user), eq(true));
    }

    @Test
    @DisplayName("O catálogo e as descrições vão no prompt; o slug é o que a IA precisa devolver")
    void shouldSendCatalogAndDescriptionsInThePrompt() {
        when(factory.resolve(eq(user), anyBoolean())).thenReturn(Optional.of(caller));
        when(caller.complete(anyString(), anyString())).thenReturn("{}");

        suggester(true).suggest(user, DESCRICOES, catalogo);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> pergunta = ArgumentCaptor.forClass(String.class);
        verify(caller).complete(system.capture(), pergunta.capture());
        assertThat(system.getValue()).contains("academia").contains("Academia").contains("transporte");
        assertThat(pergunta.getValue()).contains("fitmax").contains("posto ipiranga");
    }

    // ------------------------------------------------------------------
    // Nada aqui derruba o upload
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Chave própria ILEGÍVEL não derruba o upload: mapa vazio e nenhuma exceção sai daqui")
    void unreadableKeyMustNotBreakTheImport() {
        when(factory.resolve(eq(user), anyBoolean())).thenThrow(new IllegalArgumentException(
                "Sua chave de IA não pôde ser lida com a configuração atual do servidor."));

        AiCategorySuggester suggester = suggester(false);

        assertThatCode(() -> suggester.suggest(user, DESCRICOES, catalogo)).doesNotThrowAnyException();
        assertThat(suggester.suggest(user, DESCRICOES, catalogo)).isEmpty();
        // e a guarda que a importação consulta antes de carregar o catálogo
        // também precisa engolir a falha, em vez de propagá-la
        assertThatCode(() -> suggester.appliesTo(user)).doesNotThrowAnyException();
        assertThat(suggester.appliesTo(user)).isFalse();
    }

    @Test
    @DisplayName("Provedor recusando a chamada não derruba o upload")
    void providerFailureMustNotBreakTheImport() {
        when(factory.resolve(eq(user), anyBoolean())).thenReturn(Optional.of(caller));
        when(caller.complete(anyString(), anyString())).thenThrow(
                new AiProviderException(AiProviderException.Reason.AUTH, "O provedor recusou a chave cadastrada."));

        assertThat(suggester(false).suggest(user, DESCRICOES, catalogo)).isEmpty();
    }

    @Test
    @DisplayName("Resposta que não é JSON nenhum vira mapa vazio, não exceção")
    void garbageReplyMustNotBreakTheImport() {
        when(factory.resolve(eq(user), anyBoolean())).thenReturn(Optional.of(caller));
        when(caller.complete(anyString(), anyString())).thenReturn("desculpe, não consegui classificar");

        assertThat(suggester(true).suggest(user, DESCRICOES, catalogo)).isEmpty();
    }

    @Test
    @DisplayName("Resposta nula do provedor vira mapa vazio")
    void nullReplyMustNotBreakTheImport() {
        when(factory.resolve(eq(user), anyBoolean())).thenReturn(Optional.of(caller));
        when(caller.complete(anyString(), anyString())).thenReturn(null);

        assertThat(suggester(true).suggest(user, DESCRICOES, catalogo)).isEmpty();
    }

    // ------------------------------------------------------------------
    // A IA não manda no catálogo
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Slug inventado pela IA é descartado; o que está no catálogo passa")
    void shouldDropSlugsOutsideTheCatalog() {
        when(factory.resolve(eq(user), anyBoolean())).thenReturn(Optional.of(caller));
        when(caller.complete(anyString(), anyString()))
                .thenReturn("{\"fitmax\":\"academia\",\"posto ipiranga\":\"criptomoedas-do-tio\"}");

        Map<String, String> sugestoes = suggester(true).suggest(user, DESCRICOES, catalogo);

        assertThat(sugestoes).containsExactly(Map.entry("fitmax", "academia"));
    }

    @Test
    @DisplayName("JSON embrulhado em cerca de código ainda é lido — modelos fazem isso o tempo todo")
    void shouldUnwrapMarkdownFence() {
        when(factory.resolve(eq(user), anyBoolean())).thenReturn(Optional.of(caller));
        when(caller.complete(anyString(), anyString()))
                .thenReturn("```json\n{\"fitmax\": \"academia\"}\n```");

        assertThat(suggester(true).suggest(user, DESCRICOES, catalogo))
                .containsExactly(Map.entry("fitmax", "academia"));
    }

    @Test
    @DisplayName("Sem descrições ou sem catálogo não se resolve IA nenhuma — nem uma consulta é gasta")
    void shouldNotResolveWithoutWork() {
        AiCategorySuggester suggester = suggester(true);

        assertThat(suggester.suggest(user, List.of(), catalogo)).isEmpty();
        assertThat(suggester.suggest(user, DESCRICOES, List.of())).isEmpty();

        verifyNoInteractions(factory);
    }
}
