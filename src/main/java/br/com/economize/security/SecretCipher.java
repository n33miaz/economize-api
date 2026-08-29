package br.com.economize.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cifra de segredos guardados no banco — EC-107.
 *
 * <p><b>Por que AES-256-GCM do próprio JDK.</b> A regra da casa é não ganhar
 * dependência sem justificativa forte, e aqui não há justificativa nenhuma:
 * {@code javax.crypto} do JDK 17 traz AES/GCM com autenticação embutida, que é
 * exatamente o primitivo certo para "cifrar um valor curto e detectar
 * adulteração". Trazer um cofre externo (KMS, Vault) resolveria um problema que
 * este projeto não tem — um único deploy, um único operador — e custaria uma
 * dependência de rede no caminho de cada chamada de IA.
 *
 * <p><b>De onde vem a chave-mestra.</b> Da variável de ambiente
 * {@code SECRET_ENCRYPTION_KEY} (32 bytes em Base64), no painel do Render, junto
 * de todos os outros segredos do projeto. É o mesmo canal do JWT_SECRET e da
 * DB_PASSWORD: se ele estiver comprometido, o cofre é o menor dos problemas.
 *
 * <p><b>O que acontece se a chave-mestra sumir ou mudar.</b> Nunca lixo
 * silencioso — em nenhum dos dois casos:
 * <ul>
 *   <li><b>Sumiu</b> (variável ausente): o cofre nasce indisponível, a aplicação
 *   SOBE do mesmo jeito — deploy existente não pode quebrar por causa de uma
 *   feature nova — e cadastrar chave própria responde 503 dizendo isso. As
 *   linhas já gravadas continuam intactas no banco, esperando a variável voltar.</li>
 *   <li><b>Mudou</b>: a decifragem falha de forma explícita ({@link Unreadable}),
 *   porque a GCM autentica. O usuário vê "não conseguimos ler sua chave,
 *   recadastre" e a IA NÃO cai em silêncio na chave do servidor — trocar o
 *   destino dos dados do usuário sem avisar seria pior do que falhar.</li>
 *   <li><b>Está malformada</b> (variável presente, valor inválido): a aplicação
 *   NÃO sobe. Segredo escrito com chave errada é dano permanente, e um erro de
 *   digitação no painel tem que aparecer no boot, não seis meses depois.</li>
 * </ul>
 *
 * <p><b>Rotação.</b> Não está implementada nesta rodada — não há tarefa de
 * reescrita nem rota de administração —, mas é POSSÍVEL sem downtime porque cada
 * envelope carrega o id da chave que o cifrou:
 * {@code SECRET_ENCRYPTION_KEY_ID} nomeia a chave ativa (tudo novo é escrito com
 * ela) e {@code SECRET_ENCRYPTION_PREVIOUS_KEYS} guarda as antigas, só para
 * leitura. O procedimento, todo ele manual: publicar a chave nova como ativa
 * mantendo a velha na lista de anteriores; deixar os segredos migrarem sozinhos
 * (todo save reescreve com a ativa); acompanhar o saldo no SQL do Supabase com
 * {@code SELECT master_key_id, count(*) FROM user_ai_settings GROUP BY 1} — é
 * para isso que a coluna existe, e é por isso que ela é uma cópia do id que já
 * está no envelope; e só quando a chave velha zerar, tirá-la do ambiente.
 *
 * <p><b>O texto em claro nunca é guardado em memória entre chamadas.</b> Não há
 * cache de chave decifrada: o custo de um AES de 40 bytes é microssegundos, e um
 * cache seria uma cópia de segredo viva por tempo indeterminado em troca de nada.
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String ENVELOPE_VERSION = "v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;   // AES-256
    private static final int IV_BYTES = 12;    // tamanho recomendado para GCM
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, SecretKeySpec> keyring = new LinkedHashMap<>();
    private final String activeKeyId;

    public SecretCipher(
            @Value("${economize.secrets.key:}") String activeKey,
            @Value("${economize.secrets.key-id:k1}") String activeKeyId,
            @Value("${economize.secrets.previous-keys:}") String previousKeys) {

        this.activeKeyId = normalizeKeyId(activeKeyId);

        // As anteriores entram primeiro para que a ativa vença um id repetido —
        // configuração ambígua não pode virar "depende da ordem do parse"
        for (String entry : previousKeys.split(",")) {
            String pair = entry.trim();
            if (pair.isEmpty()) continue;
            int separator = pair.indexOf(':');
            if (separator <= 0 || separator == pair.length() - 1) {
                throw new IllegalStateException(
                        "SECRET_ENCRYPTION_PREVIOUS_KEYS malformada: use id:base64,id:base64");
            }
            String id = normalizeKeyId(pair.substring(0, separator));
            keyring.put(id, parseKey(id, pair.substring(separator + 1)));
        }

        if (!activeKey.isBlank()) {
            keyring.put(this.activeKeyId, parseKey(this.activeKeyId, activeKey));
        }

        if (keyring.isEmpty()) {
            // INFO e não WARN: instalação sem BYOK é um cenário legítimo, e o
            // servidor segue usando a chave dele como sempre fez
            log.info("Cofre de segredos sem chave-mestra — chaves próprias de IA ficam indisponíveis "
                    + "nesta instalação (defina SECRET_ENCRYPTION_KEY para habilitar)");
        } else if (!keyring.containsKey(this.activeKeyId)) {
            throw new IllegalStateException("SECRET_ENCRYPTION_KEY_ID aponta para uma chave que não foi "
                    + "fornecida — a chave ativa precisa estar em SECRET_ENCRYPTION_KEY");
        }
    }

    /** Há chave-mestra? Sem ela, cifrar é impossível e a feature responde 503. */
    public boolean isAvailable() {
        return keyring.containsKey(activeKeyId);
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    /**
     * Cifra com a chave ativa e devolve o envelope autodescritivo.
     *
     * @param context dado autenticado adicional (AAD) — ver {@link #decrypt}
     */
    public String encrypt(String plaintext, String context) {
        SecretKeySpec key = keyring.get(activeKeyId);
        if (key == null) {
            throw new IllegalStateException("Cofre de segredos indisponível: SECRET_ENCRYPTION_KEY não definida");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return String.join(":", ENVELOPE_VERSION, activeKeyId, encode(iv), encode(sealed));
        } catch (Exception e) {
            // sem o texto em claro e sem a mensagem original na cadeia: exceção
            // de provider de cripto às vezes ecoa tamanho e formato do material
            throw new IllegalStateException("Falha ao cifrar segredo (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Decifra o envelope. O {@code context} precisa ser BYTE A BYTE o mesmo usado
     * ao cifrar — é o dado autenticado da GCM, e no EC-107 ele é o UUID do dono
     * da linha. Isso amarra o segredo ao usuário: mover o texto cifrado de uma
     * linha para outra no banco não entrega a chave de ninguém, quebra a
     * autenticação e cai aqui.
     *
     * @throws Unreadable envelope de formato desconhecido, chave-mestra ausente,
     *                    adulteração ou contexto diferente
     */
    public String decrypt(String envelope, String context) {
        String[] parts = envelope == null ? new String[0] : envelope.split(":");
        if (parts.length != 4 || !ENVELOPE_VERSION.equals(parts[0])) {
            throw new Unreadable("Formato de envelope não reconhecido");
        }
        SecretKeySpec key = keyring.get(parts[1]);
        if (key == null) {
            // o id vai no texto porque é NOME de chave, não a chave
            throw new Unreadable("Chave-mestra \"" + parts[1] + "\" não está configurada neste ambiente");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, decode(parts[2])));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(decode(parts[3])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new Unreadable("Segredo não pôde ser decifrado (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Id da chave-mestra declarado no envelope, sem decifrar nada. */
    public static String keyIdOf(String envelope) {
        String[] parts = envelope == null ? new String[0] : envelope.split(":");
        return parts.length == 4 ? parts[1] : "";
    }

    private SecretKeySpec parseKey(String id, String base64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Chave-mestra \"" + id + "\" não é Base64 válido");
        }
        if (raw.length != KEY_BYTES) {
            // o tamanho não é segredo e é a informação que conserta o problema
            throw new IllegalStateException("Chave-mestra \"" + id + "\" tem " + raw.length
                    + " bytes; AES-256 exige exatamente " + KEY_BYTES
                    + " (gere com: openssl rand -base64 32)");
        }
        return new SecretKeySpec(raw, "AES");
    }

    private static String normalizeKeyId(String id) {
        String trimmed = id == null ? "" : id.trim();
        if (trimmed.isEmpty() || trimmed.contains(":") || trimmed.length() > 32) {
            throw new IllegalStateException(
                    "Id de chave-mestra inválido: use até 32 caracteres, sem \":\"");
        }
        return trimmed;
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    /**
     * O segredo está lá mas não pode ser lido. Existe para o chamador NUNCA
     * confundir "não tem chave cadastrada" com "tem, e não conseguimos abrir" —
     * a primeira cai no servidor, a segunda tem que aparecer para o usuário.
     */
    public static class Unreadable extends RuntimeException {
        public Unreadable(String message) {
            super(message);
        }
    }
}
