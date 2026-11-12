package br.com.economize.security;

import br.com.economize.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;

@Component
public class JwtUtil {

    /**
     * Marca do desafio de segundo fator. Um token com esta claim NÃO é sessão:
     * o filtro de autenticação o recusa, e só o endpoint do segundo passo o
     * aceita. Sem essa separação, o desafio emitido no primeiro passo seria um
     * token válido para a API inteira — o segundo fator viraria enfeite.
     */
    public static final String MFA_PENDING_CLAIM = "mfa_pending";

    /**
     * Cinco minutos: tempo de pegar o celular, abrir o autenticador e digitar.
     * Mais do que isso é janela viva demais para um desafio que já passou pela
     * senha e só espera seis dígitos.
     */
    private static final long MFA_CHALLENGE_MILLIS = 5 * 60 * 1000L;

    /**
     * Versão da sessão do dono no momento da emissão (V45). O filtro recusa o
     * token se a conta já estiver em outra versão: é assim que trocar a senha
     * ou "sair de todos os aparelhos" derruba tokens que já estão na rua.
     */
    public static final String SESSION_VERSION_CLAIM = "sv";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expirationTime;

    @Value("${jwt.issuer:economize}")
    private String issuer;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    /** Sessão da conta, carimbada com a versão atual dela. É o que login, MFA e cadastro emitem. */
    public String generateToken(User user) {
        return generateToken(user.getEmail(), user.getSessionVersion());
    }

    /**
     * Sessão na versão 0 — a de quem nunca revogou. Fica para os testes que só
     * precisam de "um token válido" para uma conta; se um dia for usado para
     * uma conta que já revogou, o token nasce recusado (falha fechada), em vez
     * de nascer valendo.
     */
    public String generateToken(String email) {
        return generateToken(email, 0);
    }

    private String generateToken(String email, int sessionVersion) {
        return Jwts.builder()
                .issuer(issuer)
                .subject(email)
                .claims(Map.of(SESSION_VERSION_CLAIM, sessionVersion))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Desafio de segundo fator: prova que a senha passou, e não abre mais nada.
     * Leva a versão da sessão também: sem ela, um desafio obtido antes de a
     * vítima redefinir a senha ainda poderia ser completado nos 5 minutos dele
     * e virar sessão nova, já na versão nova.
     */
    public String generateMfaChallenge(User user) {
        return Jwts.builder()
                .issuer(issuer)
                .subject(user.getEmail())
                .claims(Map.of(MFA_PENDING_CLAIM, true, SESSION_VERSION_CLAIM, user.getSessionVersion()))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + MFA_CHALLENGE_MILLIS))
                .signWith(getSigningKey())
                .compact();
    }

    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractClaims(token);
            // desafio de MFA não é sessão: quem pergunta "este token vale?"
            // está sempre perguntando por acesso à API
            if (Boolean.TRUE.equals(claims.get(MFA_PENDING_CLAIM, Boolean.class))) return false;
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * E-mail dentro de um desafio de MFA válido, ou {@code null}. Recusa token
     * de sessão comum: usar a sessão de alguém como desafio deixaria o segundo
     * passo aceitar quem nunca fez o primeiro.
     */
    public String emailFromMfaChallenge(String token) {
        MfaChallenge challenge = parseMfaChallenge(token);
        return challenge == null ? null : challenge.email();
    }

    /** O que um desafio de MFA válido carrega: de quem é e em que versão de sessão nasceu. */
    public record MfaChallenge(String email, int sessionVersion) {
    }

    /** Desafio de MFA válido, ou {@code null} — mesmas recusas de {@link #emailFromMfaChallenge}. */
    public MfaChallenge parseMfaChallenge(String token) {
        try {
            Claims claims = extractClaims(token);
            if (!Boolean.TRUE.equals(claims.get(MFA_PENDING_CLAIM, Boolean.class))) return null;
            if (claims.getExpiration().before(new Date())) return null;
            return new MfaChallenge(claims.getSubject(), sessionVersionOf(claims));
        } catch (Exception e) {
            return null;
        }
    }

    public String extractEmail(String token) {
        return extractClaims(token).getSubject();
    }

    /**
     * Versão da sessão gravada no token. Sem a claim (token emitido antes da
     * V45) conta como 0: assim o token antigo vale só enquanto a conta nunca
     * revogou, e a primeira revogação o derruba junto com os outros. Lido como
     * {@link Number} porque o parser pode devolver Integer ou Long.
     */
    public static int sessionVersionOf(Claims claims) {
        Object value = claims.get(SESSION_VERSION_CLAIM);
        return value instanceof Number n ? n.intValue() : 0;
    }
}
