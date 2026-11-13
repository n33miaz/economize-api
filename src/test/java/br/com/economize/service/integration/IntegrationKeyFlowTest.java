package br.com.economize.service.integration;

import br.com.economize.model.IntegrationKey;
import br.com.economize.model.ShoppingItem;
import br.com.economize.model.ShoppingTrip;
import br.com.economize.model.User;
import br.com.economize.repository.IntegrationKeyRepository;
import br.com.economize.repository.ShoppingItemRepository;
import br.com.economize.repository.ShoppingTripRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.security.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A chave de integração de ponta a ponta (V44): aplicação inteira, banco de
 * verdade, requisição HTTP real.
 *
 * <p>Existe porque as outras duas suítes dublam justamente a costura: a de
 * serviço dubla o banco, a de rotas dubla o serviço. O que só aparece com tudo
 * ligado é o que mais importa aqui — que o segredo devolvido pelo POST é o que
 * autentica, que revogar corta na chamada seguinte, e que a consulta do
 * histórico entrega as compras do dono da chave e de mais ninguém.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Banco PRÓPRIO desta suíte. O H2 padrão dos testes ("testdb") é um só
        // para todos os contextos, e cada contexto que fecha o derruba com
        // create-drop: com o contexto desta suíte em cache, as tabelas sumiam
        // no meio da execução completa ("Table USERS not found"), embora a
        // suíte passasse sozinha
        properties = "spring.datasource.url=jdbc:h2:mem:integration-keys;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@DisplayName("Chave de integração de ponta a ponta (V44)")
class IntegrationKeyFlowTest {

    private static final String KEYS = "/api/v1/integration-keys";
    private static final String TRIPS = "/api/v1/integration/shopping/trips";
    private static final String ADVICE = "/api/v1/integration/shopping/advice";

    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private IntegrationKeyRepository keyRepository;
    @Autowired
    private ShoppingTripRepository tripRepository;
    @Autowired
    private ShoppingItemRepository itemRepository;

    private WebTestClient client;
    private User ana;
    private User bruno;

    @BeforeEach
    void setUp() {
        // A primeira análise de renda sobe caches e consultas a frio: o prazo
        // padrão de 5 s do cliente de teste mediria a máquina, não a rota
        client = webTestClient.mutate().responseTimeout(Duration.ofSeconds(60)).build();
        // E-mails únicos por teste: o H2 em memória é compartilhado pela suíte
        ana = novoUsuario("ana");
        bruno = novoUsuario("bruno");
    }

    private User novoUsuario(String nome) {
        return userRepository.save(User.builder()
                .name(nome)
                .email(nome + "-" + UUID.randomUUID() + "@economize.app")
                .password("nao-importa")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
    }

    private String jwt(User user) {
        return "Bearer " + jwtUtil.generateToken(user.getEmail());
    }

    /** Cria a chave pela rota, como o dono faria, e devolve a resposta. */
    private JsonNode criarChave(User user, String nome) {
        return client.post().uri(KEYS).header(HttpHeaders.AUTHORIZATION, jwt(user))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", nome))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private ShoppingTrip compra(User dono, String clientId, String loja, String totalDaNota) {
        OffsetDateTime inicio = OffsetDateTime.of(2026, 9, 21, 9, 0, 0, 0, ZoneOffset.UTC);
        return tripRepository.save(ShoppingTrip.builder()
                .userId(dono.getId()).clientId(clientId).storeName(loja)
                .status(ShoppingTrip.Status.CLOSED)
                .startedAt(inicio).closedAt(inicio.plusHours(1))
                .receiptTotal(totalDaNota == null ? null : new BigDecimal(totalDaNota))
                .build());
    }

    private void item(ShoppingTrip trip, String clientId, String nome, String normalizado,
                      String quantidade, String preco, boolean pego, boolean removido) {
        itemRepository.save(ShoppingItem.builder()
                .tripId(trip.getId()).clientId(clientId).name(nome).normalizedName(normalizado)
                .quantity(new BigDecimal(quantidade))
                .unitPrice(preco == null ? null : new BigDecimal(preco))
                .checked(pego).deleted(removido)
                .clientUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
    }

    private JsonNode pagina(String segredo, String query) {
        return client.get().uri(TRIPS + query).header(HttpHeaders.AUTHORIZATION, "Bearer " + segredo)
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    // ------------------------------------------------------------ o segredo

    @Test
    @DisplayName("O POST devolve o segredo uma vez; o banco guarda só o hash, e a lista não o traz")
    void segredoSoNaCriacao() {
        JsonNode criada = criarChave(ana, "UniOver");
        String segredo = criada.get("secret").asText();
        assertThat(segredo).matches("^eco_[A-Za-z0-9_-]{43}$");

        IntegrationKey linha = keyRepository.findById(UUID.fromString(criada.get("key").get("id").asText()))
                .orElseThrow();
        assertThat(linha.getKeyHash()).isEqualTo(IntegrationKeyService.hash(segredo));
        assertThat(linha.getPrefix()).isEqualTo(segredo.substring(0, 12));
        assertThat(List.of(linha.getKeyHash(), linha.getPrefix(), linha.getName())).doesNotContain(segredo);

        String lista = client.get().uri(KEYS).header(HttpHeaders.AUTHORIZATION, jwt(ana))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(lista).contains("UniOver").contains(linha.getPrefix())
                .doesNotContain(segredo).doesNotContain(linha.getKeyHash());
    }

    @Test
    @DisplayName("A sexta chave ativa responde 409; revogando uma, cabe outra")
    void tetoDeCinco() {
        String primeira = null;
        for (int i = 1; i <= 5; i++) {
            JsonNode criada = criarChave(ana, "chave " + i);
            if (primeira == null) primeira = criada.get("key").get("id").asText();
        }
        client.post().uri(KEYS).header(HttpHeaders.AUTHORIZATION, jwt(ana))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "sexta"))
                .exchange().expectStatus().isEqualTo(409);
        // O teto é por pessoa: as cinco da Ana não tiram vaga do Bruno
        criarChave(bruno, "do bruno");

        client.delete().uri(KEYS + "/" + primeira).header(HttpHeaders.AUTHORIZATION, jwt(ana))
                .exchange().expectStatus().isNoContent();
        criarChave(ana, "no lugar da revogada");
    }

    // ------------------------------------------------------------ a fronteira

    @Test
    @DisplayName("A chave real não vale em rota normal, e o JWT real não vale na integração")
    void fronteiraEntreCredenciais() {
        String segredo = criarChave(ana, "UniOver").get("secret").asText();

        pagina(segredo, "");
        client.get().uri("/api/v1/shopping/trips").header(HttpHeaders.AUTHORIZATION, "Bearer " + segredo)
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + segredo)
                .exchange().expectStatus().isUnauthorized();
        client.get().uri(KEYS).header(HttpHeaders.AUTHORIZATION, "Bearer " + segredo)
                .exchange().expectStatus().isUnauthorized();

        client.get().uri(TRIPS).header(HttpHeaders.AUTHORIZATION, jwt(ana))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri(ADVICE).header(HttpHeaders.AUTHORIZATION, jwt(ana))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Revogar corta na chamada seguinte; quem não é o dono não revoga")
    void revogacao() {
        JsonNode criada = criarChave(ana, "UniOver");
        String segredo = criada.get("secret").asText();
        String id = criada.get("key").get("id").asText();
        pagina(segredo, "");

        // O Bruno tenta revogar a chave da Ana: para ele ela não existe
        client.delete().uri(KEYS + "/" + id).header(HttpHeaders.AUTHORIZATION, jwt(bruno))
                .exchange().expectStatus().isNotFound();
        pagina(segredo, "");

        client.delete().uri(KEYS + "/" + id).header(HttpHeaders.AUTHORIZATION, jwt(ana))
                .exchange().expectStatus().isNoContent();
        client.get().uri(TRIPS).header(HttpHeaders.AUTHORIZATION, "Bearer " + segredo)
                .exchange().expectStatus().isUnauthorized();
        client.get().uri(ADVICE).header(HttpHeaders.AUTHORIZATION, "Bearer " + segredo)
                .exchange().expectStatus().isUnauthorized();

        client.get().uri(KEYS).header(HttpHeaders.AUTHORIZATION, jwt(ana))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].revoked").isEqualTo(true)
                .jsonPath("$[0].revokedAt").exists()
                // O uso de antes da revogação ficou registrado
                .jsonPath("$[0].lastUsedAt").exists();
    }

    // ------------------------------------------------------------ as leituras

    @Test
    @DisplayName("O histórico traz as compras do dono da chave — e só dele — página a página")
    void historicoPaginadoEIsolado() {
        ShoppingTrip primeira = compra(ana, "c1", "Mercado da Esquina", "58.40");
        item(primeira, "i1", "Café Pilão 500g", "cafe pilao 500g", "2", "18.9950", true, false);
        item(primeira, "i2", "Tomate", "tomate", "1.500", "7.99", true, false);
        item(primeira, "i3", "Azeite", "azeite", "1", "42.00", false, false);
        item(primeira, "i4", "Removido", "removido", "1", "5.00", true, true);
        compra(ana, "c2", "Atacado", null);
        compra(ana, "c3", "Feira", null);
        ShoppingTrip doBruno = compra(bruno, "c1", "Mercado do Bruno", "99.00");
        item(doBruno, "i1", "Segredo do Bruno", "segredo do bruno", "1", "99.00", true, false);

        String chaveDaAna = criarChave(ana, "UniOver").get("secret").asText();
        String chaveDoBruno = criarChave(bruno, "UniOver").get("secret").asText();

        // Página 1: as duas mais antigas, com cursor para seguir
        JsonNode p1 = pagina(chaveDaAna, "?limit=2");
        assertThat(p1.get("trips")).hasSize(2);
        assertThat(p1.get("hasMore").asBoolean()).isTrue();
        JsonNode c1 = p1.get("trips").get(0);
        assertThat(c1.get("id").asText()).isEqualTo(primeira.getId().toString());
        assertThat(c1.get("clientId").asText()).isEqualTo("c1");
        assertThat(c1.get("own").asBoolean()).isTrue();
        assertThat(c1.get("storeName").asText()).isEqualTo("Mercado da Esquina");
        assertThat(c1.get("startedAt").asText()).isEqualTo("2026-09-21T09:00:00Z");
        assertThat(c1.get("closedAt").asText()).isEqualTo("2026-09-21T10:00:00Z");
        // 2 × 18,995 + 1,5 × 7,99 = 49,975 → 49,98; o azeite não foi pego e o
        // removido não conta. O que saiu de verdade é a nota: 58,40
        assertThat(c1.get("itemsTotalCents").asLong()).isEqualTo(4998);
        assertThat(c1.get("receiptTotalCents").asLong()).isEqualTo(5840);
        assertThat(c1.get("paidTotalCents").asLong()).isEqualTo(5840);

        // Item removido não sai; o visto-e-não-levado sai, marcado
        JsonNode itens = c1.get("items");
        assertThat(itens).hasSize(3);
        assertThat(itens.findValuesAsText("clientId")).containsExactly("i1", "i2", "i3");
        JsonNode cafe = itens.get(0);
        assertThat(cafe.get("name").asText()).isEqualTo("Café Pilão 500g");
        assertThat(cafe.get("normalizedName").asText()).isEqualTo("cafe pilao 500g");
        assertThat(cafe.get("quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(cafe.get("unitPriceCents").asLong()).isEqualTo(1900);
        assertThat(cafe.get("lineTotalCents").asLong()).isEqualTo(3799);
        assertThat(cafe.get("checked").asBoolean()).isTrue();
        assertThat(itens.get(1).get("quantity").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(itens.get(2).get("checked").asBoolean()).isFalse();

        // Página 2: a que faltava, sem repetir — e o cursor continua vindo
        JsonNode p2 = pagina(chaveDaAna, "?limit=2&cursor=" + p1.get("nextCursor").asText());
        assertThat(p2.get("trips")).hasSize(1);
        assertThat(p2.get("hasMore").asBoolean()).isFalse();
        assertThat(p2.get("nextCursor").asText()).isNotBlank();
        // Sem nota, o que saiu é a soma do corredor (aqui, zero itens)
        assertThat(p2.get("trips").get(0).get("receiptTotalCents").isNull()).isTrue();
        assertThat(p2.get("trips").get(0).get("paidTotalCents").asLong()).isZero();

        List<String> vistas = new ArrayList<>(p1.get("trips").findValuesAsText("storeName"));
        vistas.addAll(p2.get("trips").findValuesAsText("storeName"));
        assertThat(vistas).containsExactly("Mercado da Esquina", "Atacado", "Feira");

        // Depois da última página não há nada; compra nova aparece a partir do cursor guardado
        String fim = p2.get("nextCursor").asText();
        assertThat(pagina(chaveDaAna, "?cursor=" + fim).get("trips")).isEmpty();
        compra(ana, "c4", "Padaria", null);
        assertThat(pagina(chaveDaAna, "?cursor=" + fim).get("trips").findValuesAsText("storeName"))
                .containsExactly("Padaria");

        // A chave do Bruno lê as do Bruno, e nenhuma das duas lê a do outro
        JsonNode bruno1 = pagina(chaveDoBruno, "");
        assertThat(bruno1.get("trips").findValuesAsText("storeName")).containsExactly("Mercado do Bruno");
        assertThat(pagina(chaveDaAna, "?limit=200").toString())
                .doesNotContain("Mercado do Bruno").doesNotContain("Segredo do Bruno");

        client.get().uri(TRIPS + "?cursor=isto-nao-e-cursor")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + chaveDaAna)
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("O conselho responde para o dono da chave, com ETag que rende 304")
    void conselhoComEtag() {
        String segredo = criarChave(ana, "UniOver").get("secret").asText();

        var primeira = client.get().uri(ADVICE).header(HttpHeaders.AUTHORIZATION, "Bearer " + segredo)
                .exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult();
        JsonNode corpo = primeira.getResponseBody();
        // Conta recém-criada: sem renda cadastrada, e portanto sem "livre"
        assertThat(corpo.get("generatedAt").asText()).endsWith("Z");
        assertThat(corpo.get("incomePattern").get("status").asText()).isEqualTo("NO_INCOME");
        assertThat(corpo.get("free").get("salaryKnown").asBoolean()).isFalse();
        assertThat(corpo.get("free").get("freeCents").isNull()).isTrue();

        List<String> etags = primeira.getResponseHeaders().get(HttpHeaders.ETAG);
        assertThat(etags).hasSize(1);
        client.get().uri(ADVICE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + segredo)
                .header(HttpHeaders.IF_NONE_MATCH, etags.get(0))
                .exchange()
                .expectStatus().isNotModified()
                .expectBody().isEmpty();
    }
}
