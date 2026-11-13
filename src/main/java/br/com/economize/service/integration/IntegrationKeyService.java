package br.com.economize.service.integration;

import br.com.economize.dto.integration.IntegrationKeyResponses;
import br.com.economize.exception.ResourceConflictException;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.IntegrationKey;
import br.com.economize.model.User;
import br.com.economize.repository.IntegrationKeyRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * As chaves de integração (V44): criar, listar, revogar e conferir.
 *
 * <p><b>O segredo existe em dois lugares, e nenhum é o banco:</b> na resposta
 * da criação e no sistema que o guardou. Aqui fica o SHA-256 dele. SHA-256
 * puro, sem sal nem custo, é a escolha certa para ESTE segredo: são 32 bytes
 * aleatórios, não uma senha — não há dicionário a tentar, e a conferência
 * roda a cada chamada da integração.
 *
 * <p><b>Uma chave recusada não diz por quê.</b> {@link #authenticate} devolve
 * vazio para chave malformada, desconhecida, revogada ou de conta apagada, e
 * quem chama responde o mesmo 401 para todas: distinguir os casos contaria a
 * quem está tentando que um segredo já existiu.
 */
@Service
@RequiredArgsConstructor
public class IntegrationKeyService {

    public static final String SECRET_PREFIX = "eco_";
    static final int SECRET_BYTES = 32;
    /** Quantos caracteres do segredo ficam visíveis na lista: "eco_" e mais oito. */
    static final int VISIBLE_PREFIX_LENGTH = 12;
    static final int MAX_ACTIVE_KEYS = 5;

    /**
     * De quanto em quanto tempo o "último uso" é regravado. A marca serve para
     * a pessoa ver que a chave está viva (ou esquecida), e para isso minutos
     * bastam — gravar a cada chamada transformaria toda leitura em escrita.
     */
    static final Duration LAST_USED_GRANULARITY = Duration.ofMinutes(5);

    /** 32 bytes em base64url sem preenchimento são sempre 43 caracteres. */
    private static final Pattern SECRET_FORMAT = Pattern.compile("^eco_[A-Za-z0-9_-]{43}$");

    static final String USER_NOT_FOUND = "Usuário não encontrado";
    static final String KEY_NOT_FOUND = "Chave não encontrada";
    static final String TOO_MANY_KEYS =
            "Você já tem " + MAX_ACTIVE_KEYS + " chaves ativas — revogue uma antes de criar outra";

    private final IntegrationKeyRepository repository;
    private final UserRepository userRepository;
    private final SecureRandom random = new SecureRandom();

    // ------------------------------------------------------------ gestão

    /** Cria a chave e devolve o segredo — a única vez em que ele sai daqui. */
    @Transactional
    public IntegrationKeyResponses.IntegrationKeyCreated create(String email, String name) {
        UUID userId = requireUser(email).getId();
        if (repository.countByUserIdAndRevokedAtIsNull(userId) >= MAX_ACTIVE_KEYS) {
            throw new ResourceConflictException(TOO_MANY_KEYS);
        }

        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        String secret = SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        IntegrationKey saved = repository.save(IntegrationKey.builder()
                .userId(userId)
                .name(name.trim())
                .keyHash(hash(secret))
                .prefix(secret.substring(0, VISIBLE_PREFIX_LENGTH))
                .build());
        return new IntegrationKeyResponses.IntegrationKeyCreated(toItem(saved), secret);
    }

    @Transactional(readOnly = true)
    public List<IntegrationKeyResponses.IntegrationKeyItem> list(String email) {
        UUID userId = requireUser(email).getId();
        return repository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(IntegrationKeyService::toItem)
                .toList();
    }

    /**
     * Revoga. Chave de outra pessoa responde 404, e não 403: confirmar que o
     * id existe já é vazar (a regra do projeto desde o EC-037). Revogar duas
     * vezes é inofensivo — a data da primeira é a que fica.
     */
    @Transactional
    public void revoke(String email, UUID id) {
        UUID userId = requireUser(email).getId();
        IntegrationKey key = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException(KEY_NOT_FOUND));
        if (!key.isRevoked()) {
            key.setRevokedAt(now());
            repository.save(key);
        }
    }

    // ------------------------------------------------------------ conferência

    /** O e-mail do dono da chave; vazio quando ela não autentica ninguém. */
    @Transactional
    public Optional<String> authenticate(String secret) {
        return authenticate(secret, now());
    }

    /** Sobrecarga com "agora" explícito — é o que torna o intervalo do último uso testável. */
    Optional<String> authenticate(String secret, OffsetDateTime now) {
        // O formato é conferido ANTES de ir ao banco: lixo no cabeçalho (e todo
        // JWT de sessão apresentado na rota errada) não custa uma consulta
        if (secret == null || !SECRET_FORMAT.matcher(secret).matches()) {
            return Optional.empty();
        }
        String presented = hash(secret);
        Optional<IntegrationKey> found = repository.findByKeyHash(presented);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IntegrationKey key = found.get();
        // A busca já foi pelo hash; a comparação em tempo constante é a
        // conferência final, e não depende de como o banco compara texto
        if (!MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.US_ASCII),
                key.getKeyHash().getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        if (key.isRevoked()) {
            return Optional.empty();
        }
        Optional<String> email = userRepository.findById(key.getUserId()).map(User::getEmail);
        if (email.isEmpty()) {
            return Optional.empty();
        }

        OffsetDateTime staleBefore = now.minus(LAST_USED_GRANULARITY);
        if (key.getLastUsedAt() == null || key.getLastUsedAt().isBefore(staleBefore)) {
            repository.touchLastUsed(key.getId(), now, staleBefore);
        }
        return email;
    }

    // ------------------------------------------------------------ apoio

    static String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 é obrigatório em toda JVM; chegar aqui é instalação quebrada
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND));
    }

    private static IntegrationKeyResponses.IntegrationKeyItem toItem(IntegrationKey key) {
        return new IntegrationKeyResponses.IntegrationKeyItem(
                key.getId(), key.getName(), key.getPrefix(),
                key.getCreatedAt(), key.getLastUsedAt(), key.getRevokedAt(), key.isRevoked());
    }
}
