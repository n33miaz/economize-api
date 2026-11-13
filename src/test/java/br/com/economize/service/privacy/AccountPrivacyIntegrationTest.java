package br.com.economize.service.privacy;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.FamilyGroup;
import br.com.economize.model.FamilyMember;
import br.com.economize.model.IntegrationKey;
import br.com.economize.model.User;
import br.com.economize.model.UserAiSettings;
import br.com.economize.model.UserMfa;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.FamilyGroupRepository;
import br.com.economize.repository.FamilyMemberRepository;
import br.com.economize.repository.IntegrationKeyRepository;
import br.com.economize.repository.UserAiSettingsRepository;
import br.com.economize.repository.UserMfaRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.security.MfaSecretCipher;
import br.com.economize.security.TotpGenerator;
import br.com.economize.service.ai.AiProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exportar e excluir a conta (LGPD) com a aplicação inteira: rota, filtro de
 * sessão, H2. Cada teste cria os próprios usuários (o banco em memória é
 * compartilhado entre os testes do mesmo contexto).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("LGPD: baixar os dados e excluir a conta")
class AccountPrivacyIntegrationTest {

    private static final String PASSWORD = "senha-forte-123";
    private static final String AI_KEY = "sk-chave-de-ia-em-claro-0123456789";

    @Autowired private WebTestClient client;
    @Autowired private UserRepository userRepository;
    @Autowired private BankTransactionRepository bankTransactionRepository;
    @Autowired private UserAiSettingsRepository aiSettingsRepository;
    @Autowired private UserMfaRepository mfaRepository;
    @Autowired private IntegrationKeyRepository integrationKeyRepository;
    @Autowired private FamilyGroupRepository groupRepository;
    @Autowired private FamilyMemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MfaSecretCipher mfaCipher;
    @Autowired private SchemaTables tables;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("export: um CSV por tipo de dado e o LEIA-ME, só com dados da pessoa e sem segredos")
    void exportHasOneFilePerTypeAndOnlyOwnData() throws IOException {
        User me = newUser();
        User other = newUser();
        transaction(me, "PADARIA-DO-TITULAR", "-12.5");
        transaction(other, "SEGREDO-DE-OUTRA-PESSOA", "-99");
        String aiCipher = aiSettingsRepository.save(UserAiSettings.builder().user(me)
                .provider(AiProvider.GEMINI).model("gemini-x")
                .apiKeyCipher("cifra-" + AI_KEY).masterKeyId("k1").apiKeyLast4("6789").build())
                .getApiKeyCipher();
        String mfaSecret = TotpGenerator.newSecret();
        String mfaCipherText = mfaCipher.encrypt(mfaSecret, me.getId().toString());
        mfaRepository.save(UserMfa.builder().user(me).secretCipher(mfaCipherText).enabled(false).build());
        String keyHash = "a".repeat(64);
        integrationKeyRepository.save(IntegrationKey.builder().userId(me.getId())
                .name("Atalho").keyHash(keyHash).prefix("eco_1234").build());

        byte[] zip = client.get().uri("/api/v1/users/me/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login(me))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/zip")
                .expectHeader().value(HttpHeaders.CONTENT_DISPOSITION, v -> assertThat(v).contains("attachment"))
                .expectBody(byte[].class).returnResult().getResponseBody();

        Map<String, String> files = unzip(zip);
        List<String> expected = AccountDataExporter.EXPORTS.stream()
                .filter(e -> tables.exists(e.table()))
                .map(AccountDataExporter.Export::file)
                .toList();
        assertThat(expected).hasSizeGreaterThan(30);
        assertThat(files.keySet()).containsAll(expected).contains("LEIA-ME.txt")
                .hasSize(expected.size() + 1);

        String lancamentos = files.get("lancamentos.csv");
        assertThat(lancamentos).startsWith("﻿").contains(";").contains("PADARIA-DO-TITULAR")
                .doesNotContain("SEGREDO-DE-OUTRA-PESSOA");
        // valor numérico sai com ponto decimal e sem apóstrofo de fórmula
        assertThat(lancamentos).contains(";-12.5000;");
        assertThat(files.get("perfil.csv")).contains(me.getEmail()).doesNotContain(other.getEmail());
        assertThat(files.get("seguranca_2fa.csv")).contains("enabled").doesNotContain("secret_cipher");
        assertThat(files.get("chaves_integracao.csv")).contains("Atalho").doesNotContain("key_hash");

        String all = String.join("\n", files.values());
        String passwordHash = userRepository.findById(me.getId()).orElseThrow().getPassword();
        assertThat(all)
                .doesNotContain(passwordHash)
                .doesNotContain(aiCipher)
                .doesNotContain(AI_KEY)
                .doesNotContain(mfaSecret)
                .doesNotContain(mfaCipherText)
                .doesNotContain(keyHash)
                .doesNotContain(other.getId().toString());
    }

    @Test
    @DisplayName("delete: senha errada é 400 e nada é apagado")
    void wrongPasswordIsRejected() {
        User me = newUser();
        transaction(me, "FICA", "-1");

        delete(login(me), Map.of("password", "errada"))
                .expectStatus().isBadRequest();

        assertThat(userRepository.findById(me.getId())).isPresent();
        assertThat(bankTransactionRepository.countByUserId(me.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("delete: apaga tudo da pessoa, preserva os outros, e o token deixa de valer")
    void deleteErasesEverythingAndRevokesTheToken() {
        User me = newUser();
        User other = newUser();
        transaction(me, "MEU", "-10");
        transaction(other, "DO OUTRO", "-20");
        aiSettingsRepository.save(UserAiSettings.builder().user(me).provider(AiProvider.GEMINI)
                .model("m").apiKeyCipher("c").masterKeyId("k1").build());
        integrationKeyRepository.save(IntegrationKey.builder().userId(me.getId())
                .name("Chave").keyHash("b".repeat(64)).prefix("eco_5678").build());
        // sou dono de uma casa só minha: a exclusão a desfaz junto
        FamilyGroup group = groupRepository.save(FamilyGroup.builder().name("Só eu").owner(me).build());
        memberRepository.save(FamilyMember.builder().group(group).user(me)
                .role(FamilyMember.Role.OWNER).shareScope(FamilyMember.ShareScope.TOTALS).build());

        String token = login(me);
        me(token).expectStatus().isOk(); // aquece o cache da versão da sessão

        delete(token, Map.of("password", PASSWORD)).expectStatus().isNoContent();

        assertThat(rowsReferencing(me.getId())).isEmpty();
        assertThat(userRepository.findById(me.getId())).isEmpty();
        assertThat(groupRepository.findById(group.getId())).isEmpty();
        assertThat(bankTransactionRepository.countByUserId(other.getId())).isEqualTo(1);
        me(token).expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("delete: dono de casa com outros membros é recusado com 409")
    void ownerOfHouseholdWithMembersIsRefused() {
        User owner = newUser();
        User member = newUser();
        FamilyGroup group = groupRepository.save(FamilyGroup.builder().name("Casa").owner(owner).build());
        memberRepository.save(FamilyMember.builder().group(group).user(owner)
                .role(FamilyMember.Role.OWNER).shareScope(FamilyMember.ShareScope.TOTALS).build());
        memberRepository.save(FamilyMember.builder().group(group).user(member)
                .role(FamilyMember.Role.MEMBER).shareScope(FamilyMember.ShareScope.TOTALS).build());

        delete(login(owner), Map.of("password", PASSWORD))
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").value(d -> assertThat(d.toString()).contains("desfaça a casa"));

        assertThat(userRepository.findById(owner.getId())).isPresent();

        // o membro, por sua vez, sai da casa ao excluir a própria conta
        delete(login(member), Map.of("password", PASSWORD)).expectStatus().isNoContent();
        assertThat(memberRepository.existsByUserId(member.getId())).isFalse();
        assertThat(memberRepository.existsByUserId(owner.getId())).isTrue();
    }

    @Test
    @DisplayName("delete: com 2FA ligado exige o código; sem ele é 400, com ele exclui")
    void mfaCodeIsRequiredWhenEnabled() {
        User me = newUser();
        String secret = TotpGenerator.newSecret();
        mfaRepository.save(UserMfa.builder().user(me)
                .secretCipher(mfaCipher.encrypt(secret, me.getId().toString())).enabled(true).build());
        String token = jwtFor(me);

        delete(token, Map.of("password", PASSWORD)).expectStatus().isBadRequest();
        assertThat(userRepository.findById(me.getId())).isPresent();

        String code = TotpGenerator.codeAt(secret, TotpGenerator.stepAt(Instant.now()));
        delete(token, Map.of("password", PASSWORD, "code", code)).expectStatus().isNoContent();
        assertThat(userRepository.findById(me.getId())).isEmpty();
    }

    // --- apoio ---

    @Autowired private br.com.economize.security.JwtUtil jwtUtil;

    /** Com 2FA ligado o login pede o código; o token sai direto do emissor, como depois do desafio. */
    private String jwtFor(User user) {
        return jwtUtil.generateToken(userRepository.findById(user.getId()).orElseThrow());
    }

    /** Toda linha, em qualquer tabela, que ainda aponte para a pessoa por uma coluna de dono. */
    private Map<String, Integer> rowsReferencing(UUID userId) {
        Map<String, Integer> found = new HashMap<>();
        List<Map<String, Object>> columns = jdbc.queryForList(
                "select table_name, column_name from information_schema.columns "
                        + "where lower(column_name) in ('user_id', 'owner_user_id', 'created_by', 'added_by') "
                        + "and lower(table_schema) = 'public'");
        for (Map<String, Object> column : columns) {
            String table = column.get("TABLE_NAME").toString();
            String name = column.get("COLUMN_NAME").toString();
            Integer count = jdbc.queryForObject(
                    "select count(*) from " + table + " where " + name + " = ?", Integer.class, userId);
            if (count != null && count > 0) found.put(table + "." + name, count);
        }
        assertThat(columns).hasSizeGreaterThan(20);
        return found;
    }

    private User newUser() {
        return userRepository.save(User.builder()
                .name("Titular")
                .email("lgpd-" + UUID.randomUUID() + "@economize.test")
                .password(passwordEncoder.encode(PASSWORD))
                .build());
    }

    private void transaction(User user, String description, String amount) {
        bankTransactionRepository.save(BankTransaction.builder()
                .user(user)
                .transactionId(UUID.randomUUID().toString())
                .type(amount.startsWith("-") ? "DEBIT" : "CREDIT")
                .amount(new BigDecimal(amount))
                .description(description)
                .date(OffsetDateTime.now())
                .build());
    }

    private String login(User user) {
        Object token = client.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", user.getEmail(), "password", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody()
                .get("token");
        assertThat(token).isNotNull();
        return token.toString();
    }

    private WebTestClient.ResponseSpec me(String token) {
        return client.get().uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private WebTestClient.ResponseSpec delete(String token, Map<String, String> body) {
        return client.method(HttpMethod.DELETE).uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
