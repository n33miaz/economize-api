package br.com.economize.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    // 32 bytes em Base64 — a mesma chave do application.properties de teste
    private static final String KEY_1 = "dGVzdGUtZGUtY2hhdmUtbWVzdHJhLTMyLWJ5dGVzISE=";
    private static final String KEY_2 = "c2VndW5kYS1jaGF2ZS1tZXN0cmEtZGUtMzItYnl0ZXM=";
    private static final String SECRET = "sk-proj-Zm9vYmFyYmF6cXV4-1234567890abcdef";

    private static SecretCipher cipher(String key, String keyId, String previous) {
        return new SecretCipher(key, keyId, previous);
    }

    @Test
    @DisplayName("Ida e volta: o que entra em claro volta idêntico")
    void shouldRoundTrip() {
        SecretCipher cipher = cipher(KEY_1, "k1", "");
        String owner = UUID.randomUUID().toString();

        String envelope = cipher.encrypt(SECRET, owner);

        assertThat(cipher.decrypt(envelope, owner)).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("O envelope não contém o segredo, nem em pedaço reconhecível")
    void envelopeShouldNotLeakPlaintext() {
        SecretCipher cipher = cipher(KEY_1, "k1", "");
        String owner = UUID.randomUUID().toString();

        String envelope = cipher.encrypt(SECRET, owner);

        assertThat(envelope).doesNotContain(SECRET);
        assertThat(envelope).doesNotContain("sk-proj");
        assertThat(envelope).doesNotContain(SECRET.substring(SECRET.length() - 8));
    }

    @Test
    @DisplayName("Duas cifragens do mesmo segredo dão envelopes diferentes (IV novo a cada vez)")
    void shouldUseFreshIv() {
        SecretCipher cipher = cipher(KEY_1, "k1", "");
        String owner = UUID.randomUUID().toString();

        assertThat(cipher.encrypt(SECRET, owner)).isNotEqualTo(cipher.encrypt(SECRET, owner));
    }

    @Test
    @DisplayName("O envelope declara com qual chave-mestra foi cifrado — é o que torna a rotação possível")
    void envelopeShouldDeclareMasterKeyId() {
        SecretCipher cipher = cipher(KEY_1, "producao-2026", "");

        String envelope = cipher.encrypt(SECRET, "dono");

        assertThat(envelope).startsWith("v1:producao-2026:");
        assertThat(SecretCipher.keyIdOf(envelope)).isEqualTo("producao-2026");
    }

    @Test
    @DisplayName("Rotação: a chave nova cifra, a antiga continua decifrando o que já estava gravado")
    void shouldDecryptWithPreviousKeyAfterRotation() {
        String owner = UUID.randomUUID().toString();
        String antigo = cipher(KEY_1, "k1", "").encrypt(SECRET, owner);

        SecretCipher aposRotacao = cipher(KEY_2, "k2", "k1:" + KEY_1);

        assertThat(aposRotacao.decrypt(antigo, owner)).isEqualTo(SECRET);
        assertThat(SecretCipher.keyIdOf(aposRotacao.encrypt(SECRET, owner))).isEqualTo("k2");
    }

    @Test
    @DisplayName("Chave-mestra trocada sem manter a antiga: erro honesto, nunca lixo silencioso")
    void shouldFailLoudlyWhenMasterKeyIsGone() {
        String envelope = cipher(KEY_1, "k1", "").encrypt(SECRET, "dono");

        SecretCipher outraInstalacao = cipher(KEY_2, "k2", "");

        assertThatThrownBy(() -> outraInstalacao.decrypt(envelope, "dono"))
                .isInstanceOf(SecretCipher.Unreadable.class)
                .hasMessageContaining("k1");
    }

    @Test
    @DisplayName("Mesma chave-mestra, id diferente: também é erro, não decifra por sorte")
    void shouldFailWhenKeyIdIsUnknown() {
        String envelope = cipher(KEY_1, "k1", "").encrypt(SECRET, "dono");

        assertThatThrownBy(() -> cipher(KEY_1, "outro-id", "").decrypt(envelope, "dono"))
                .isInstanceOf(SecretCipher.Unreadable.class);
    }

    @Test
    @DisplayName("O segredo está amarrado ao dono: mover a linha para outro usuário no banco não abre nada")
    void shouldRefuseWhenOwnerDiffers() {
        SecretCipher cipher = cipher(KEY_1, "k1", "");
        String dono = UUID.randomUUID().toString();
        String invasor = UUID.randomUUID().toString();

        String envelope = cipher.encrypt(SECRET, dono);

        assertThatThrownBy(() -> cipher.decrypt(envelope, invasor))
                .isInstanceOf(SecretCipher.Unreadable.class);
    }

    @Test
    @DisplayName("Adulteração do texto cifrado é detectada pela autenticação da GCM")
    void shouldDetectTampering() {
        SecretCipher cipher = cipher(KEY_1, "k1", "");
        String envelope = cipher.encrypt(SECRET, "dono");
        String[] parts = envelope.split(":");

        // Vira um BIT no MEIO do corpo cifrado, não o último caractere do
        // Base64: dependendo do tamanho do segredo, o caractere final carrega
        // bits que o decodificador ignora, e a "adulteração" poderia decodificar
        // para os mesmos bytes — um teste que passa por sorte do tamanho da
        // fixture. Mexer no byte do meio não depende de nada.
        byte[] corpo = Base64.getUrlDecoder().decode(parts[3]);
        corpo[corpo.length / 2] ^= 0x01;
        String quebrado = String.join(":", parts[0], parts[1], parts[2],
                Base64.getUrlEncoder().withoutPadding().encodeToString(corpo));

        assertThatThrownBy(() -> cipher.decrypt(quebrado, "dono"))
                .isInstanceOf(SecretCipher.Unreadable.class);
    }

    @Test
    @DisplayName("Adulteração do IV também é detectada — a tag cobre o envelope inteiro")
    void shouldDetectIvTampering() {
        SecretCipher cipher = cipher(KEY_1, "k1", "");
        String envelope = cipher.encrypt(SECRET, "dono");
        String[] parts = envelope.split(":");

        byte[] iv = Base64.getUrlDecoder().decode(parts[2]);
        iv[iv.length / 2] ^= 0x01;
        String quebrado = String.join(":", parts[0], parts[1],
                Base64.getUrlEncoder().withoutPadding().encodeToString(iv), parts[3]);

        assertThatThrownBy(() -> cipher.decrypt(quebrado, "dono"))
                .isInstanceOf(SecretCipher.Unreadable.class);
    }

    @Test
    @DisplayName("Envelope de formato desconhecido não estoura NullPointer nem passa batido")
    void shouldRejectUnknownEnvelope() {
        SecretCipher cipher = cipher(KEY_1, "k1", "");

        assertThatThrownBy(() -> cipher.decrypt(null, "dono")).isInstanceOf(SecretCipher.Unreadable.class);
        assertThatThrownBy(() -> cipher.decrypt("", "dono")).isInstanceOf(SecretCipher.Unreadable.class);
        assertThatThrownBy(() -> cipher.decrypt("v9:k1:aa:bb", "dono")).isInstanceOf(SecretCipher.Unreadable.class);
        assertThatThrownBy(() -> cipher.decrypt(SECRET, "dono")).isInstanceOf(SecretCipher.Unreadable.class);
    }

    @Test
    @DisplayName("Sem chave-mestra o cofre nasce indisponível e a aplicação continua subindo")
    void shouldStartUnavailableWithoutKey() {
        SecretCipher cipher = cipher("", "k1", "");

        assertThat(cipher.isAvailable()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt(SECRET, "dono"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECRET_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("Chave-mestra malformada derruba o boot — erro de digitação não pode virar dado ilegível")
    void shouldRefuseToStartWithBrokenKey() {
        assertThatThrownBy(() -> cipher("nao-e-base64-valido-%%%", "k1", ""))
                .isInstanceOf(IllegalStateException.class);

        // Base64 válido, tamanho errado
        assertThatThrownBy(() -> cipher("Y3VydG8=", "k1", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AES-256");

        assertThatThrownBy(() -> cipher(KEY_1, "k1", "malformada"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PREVIOUS_KEYS");
    }

    @Test
    @DisplayName("Id de chave ativa que não foi fornecida derruba o boot em vez de cifrar com outra")
    void shouldRefuseActiveKeyIdWithoutKey() {
        assertThatThrownBy(() -> cipher("", "k2", "k1:" + KEY_1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECRET_ENCRYPTION_KEY_ID");
    }

    @Test
    @DisplayName("Nenhuma mensagem de erro do cofre carrega o segredo")
    void errorMessagesShouldNeverCarryTheSecret() {
        SecretCipher cipher = cipher(KEY_1, "k1", "");
        String envelope = cipher.encrypt(SECRET, "dono");

        assertThatThrownBy(() -> cipher.decrypt(envelope, "invasor"))
                .hasMessageNotContaining(SECRET)
                .hasMessageNotContaining("sk-proj");
    }
}
