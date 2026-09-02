package br.com.economize.service.family;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * O código do convite (EC-149): como nasce, como se normaliza o que a pessoa
 * digitou e como vira o hash que o banco guarda.
 *
 * <p>Oito caracteres de um alfabeto de 32 sem ambiguidade visual (sem I/1 nem
 * O/0) — 40 bits. Não é uma senha: é um segredo de 7 dias, de uso único, cuja
 * rota de aceite está no balde caro do rate limit (10/min). A essa taxa, 2^40
 * tentativas são milhares de anos; a ergonomia de digitar do WhatsApp vale
 * mais do que bits a mais.
 */
public final class InviteCode {

    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 8;

    private InviteCode() {
    }

    public static String generate(SecureRandom random) {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * O que a pessoa digitou, no formato em que foi gerado: maiúsculas, sem
     * espaços nem hífens. O código é trocado por mensagem e digitado à mão;
     * recusar "abcd-2345" por causa do hífen seria punir quem o separou para
     * ler melhor. Nada mais é corrigido — o alfabeto já não tem O/0 nem I/1.
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        return raw.toUpperCase(Locale.ROOT).replaceAll("[\\s\\-]", "");
    }

    /** SHA-256 em hex — o único formato em que o código toca o banco. */
    public static String hash(String normalizedCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalizedCode.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 é obrigatório em toda JVM; se faltar, o ambiente está quebrado
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
