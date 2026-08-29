package br.com.economize.service.recurrence;

import br.com.economize.model.RecurringSeries;
import br.com.economize.service.statement.category.DescriptionNormalizer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reduz a descrição de uma transação à identidade da ENTIDADE recorrente, um
 * degrau acima do {@link DescriptionNormalizer}: a mesma conta muda de rótulo
 * ao longo do tempo ("Pagamento SABESP" vira "Pagamento de Convênio | SABESP"),
 * adquirentes trocam de prefixo ("Dm*spotify" / "Ebn*spotify") e a cidade no
 * sufixo varia com o processador. Casar pelo descritivo inteiro separaria em
 * várias séries o que é uma cobrança só.
 */
public final class MerchantKeyExtractor {

    private MerchantKeyExtractor() {
    }

    /** Resultado da extração: âncora conhecida OU tokens candidatos a entidade. */
    public record Extraction(String anchor, List<String> tokens, RecurringSeries.Cadence cadenceHint) {
    }

    // Entidades cujo rótulo "real" nunca se estabiliza no extrato: a fatura de
    // cartão aparece como "Fatura cartão X" e como "Pagamento Fatura - TITULAR"
    // (nada em comum além da palavra), e o salário via portabilidade não traz o
    // nome do empregador. A âncora é verificada ANTES da remoção de ruído,
    // porque "pagamento fatura" é justamente uma das frases que o normalizador
    // apaga.
    private static final List<String> ENTITY_ANCHORS = List.of("fatura", "salario");

    // Prefixos de adquirente/processador colados ao nome do estabelecimento
    private static final Set<String> ACQUIRER_PREFIXES = Set.of("mp", "dm", "ebn", "ifd", "pg", "pag");

    // "Mensal"/"Trimestral" no descritivo do plano vale mais que a média dos
    // intervalos: na troca de plano, o histórico ainda é mensal mas o descritivo
    // novo já diz qual é a cadência daqui pra frente
    private static final Map<String, RecurringSeries.Cadence> CADENCE_HINTS = Map.of(
            "semanal", RecurringSeries.Cadence.WEEKLY,
            "mensal", RecurringSeries.Cadence.MONTHLY,
            "trimestral", RecurringSeries.Cadence.QUARTERLY);

    // Quantidades de plano ("10gb", "20gb"): variam na troca de plano sem mudar
    // a entidade
    private static final Pattern UNIT_TOKEN = Pattern.compile("\\d+(gb|mb|tb|kb|un|x)");
    private static final Pattern DIGITS_ONLY = Pattern.compile("\\d+");

    // Vocabulário operacional que sobrevive ao DescriptionNormalizer mas não
    // identifica entidade nenhuma (conectivos, jargão de pagamento, sufixos
    // societários)
    private static final Set<String> STOPWORDS = Set.of(
            "pagamento", "pagto", "pgto", "recebido", "recebida", "enviado", "enviada",
            "efetuado", "efetuada", "convenio", "conta", "contas", "debito", "credito",
            "referente", "ref", "mes", "para", "com", "sem", "por", "de", "do", "da",
            "dos", "das", "em", "no", "na", "nos", "nas", "ltda", "sa", "me", "mei",
            "eireli", "epp", "cia", "banco", "bco", "pix", "ted", "doc", "transf",
            "transferencia", "cobranca", "boleto", "tarifa", "bra");

    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    private static final Pattern SYMBOLS = Pattern.compile("[^a-z0-9 ]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    public static Extraction extract(String description) {
        String light = lightNormalize(description);
        // A âncora casa por TOKEN exato, nunca por substring: "faturamento" de um
        // MEI não pode cair na série da fatura do cartão, nem "assalariado" na do
        // salário — substring fundia entidades diferentes numa série só
        Set<String> lightTokens = light.isEmpty()
                ? Set.of()
                : new HashSet<>(List.of(light.split(" ")));
        for (String anchor : ENTITY_ANCHORS) {
            if (lightTokens.contains(anchor)) {
                return new Extraction(anchor, List.of(), null);
            }
        }

        String normalized = DescriptionNormalizer.normalize(description);
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split(" ")) {
            if (!token.isBlank()) tokens.add(token);
        }

        while (!tokens.isEmpty() && ACQUIRER_PREFIXES.contains(tokens.get(0))) {
            tokens.remove(0);
        }
        stripTrailingCity(tokens);

        RecurringSeries.Cadence hint = null;
        List<String> entityTokens = new ArrayList<>();
        for (String token : tokens) {
            RecurringSeries.Cadence tokenHint = CADENCE_HINTS.get(token);
            if (tokenHint != null) {
                hint = tokenHint;
                continue;
            }
            if (token.length() < 3) continue;
            if (STOPWORDS.contains(token)) continue;
            if (UNIT_TOKEN.matcher(token).matches()) continue;
            // números soltos são agência/documento/quantidade — variam por cobrança
            if (DIGITS_ONLY.matcher(token).matches()) continue;
            // dedup preservando a ordem: "Pagamento ACME | ACME" é um token, não dois
            if (!entityTokens.contains(token)) entityTokens.add(token);
        }
        return new Extraction(null, entityTokens, hint);
    }

    /**
     * Escolhe o token que melhor identifica a entidade: o que reaparece em mais
     * meses distintos do histórico do usuário. É ele que sobrevive quando o
     * rótulo muda ("ELETROPAULO" está nas duas grafias; "AES" e "METROPOLITANA"
     * só em uma época cada). Empate decide por comprimento e depois pela
     * posição mais à direita — em nome de pessoa, o sobrenome discrimina mais
     * que o primeiro nome.
     */
    public static String dominantToken(List<String> tokens, Map<String, Integer> monthCounts) {
        String best = null;
        int bestScore = -1;
        for (String token : tokens) {
            int score = monthCounts.getOrDefault(token, 0);
            if (best == null
                    || score > bestScore
                    || (score == bestScore && token.length() > best.length())
                    || (score == bestScore && token.length() == best.length())) {
                best = token;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Chave de série para o agendamento manual (EC-096), pelo MESMO caminho da
     * detecção (extract + dominantToken) — é isso que garante que a varredura
     * concilie a série agendada com as transações reais que chegarem depois.
     * Sem histórico não há contagem de meses: o desempate do dominantToken
     * decide sozinho (todos com score zero → vence o token mais longo, depois o
     * mais à direita). Quando nada sobra (texto só de stopwords/números), cai no
     * texto normalizado sem espaços: chave que dificilmente casará com a
     * detecção, mas mantém o agendamento utilizável e a colisão de unicidade
     * coerente; vazia de verdade é rejeitada por quem chama.
     */
    public static String deriveKey(String text) {
        Extraction extraction = extract(text);
        if (extraction.anchor() != null) return extraction.anchor();
        String dominant = dominantToken(extraction.tokens(), Map.of());
        String key = dominant != null && !dominant.isBlank()
                ? dominant
                : lightNormalize(text).replace(" ", "");
        return key.length() > 160 ? key.substring(0, 160) : key;
    }

    /** Minúsculas, sem acentos e sem símbolos — mas SEM remover frases de ruído. */
    public static String lightNormalize(String description) {
        if (description == null || description.isBlank()) return "";
        String base = ACCENTS.matcher(
                Normalizer.normalize(description.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)).replaceAll("");
        return SPACES.matcher(SYMBOLS.matcher(base).replaceAll(" ")).replaceAll(" ").trim();
    }

    /** Tokens do nome do titular usados para reconhecer transferência interna. */
    public static List<String> nameTokens(String name) {
        List<String> tokens = new ArrayList<>();
        for (String token : lightNormalize(name).split(" ")) {
            if (token.length() >= 3) tokens.add(token);
        }
        return tokens;
    }

    /**
     * A contraparte é o próprio titular? Exige pelo menos dois tokens do nome
     * presentes (ou o único, para nomes de um token só): "Maria Souza" mandando
     * PIX para uma usuária "Maria Silva" não pode virar transferência interna.
     */
    public static boolean mentionsName(String lightDescription, Collection<String> nameTokens) {
        if (nameTokens.isEmpty() || lightDescription.isBlank()) return false;
        Set<String> descriptionTokens = new HashSet<>(List.of(lightDescription.split(" ")));
        int required = Math.min(2, nameTokens.size());
        int found = 0;
        for (String token : nameTokens) {
            if (descriptionTokens.contains(token)) found++;
        }
        return found >= required;
    }

    // Sufixo "cidade + Bra" dos adquirentes ("Sao Paulo Bra", "Stockholm Bra"):
    // a cidade acompanha o processador da vez, não a entidade. Remove o "bra"
    // terminal e até dois tokens de cidade antes dele, preservando ao menos um
    // token de entidade.
    private static void stripTrailingCity(List<String> tokens) {
        if (tokens.isEmpty() || !"bra".equals(tokens.get(tokens.size() - 1))) return;
        tokens.remove(tokens.size() - 1);
        int removed = 0;
        while (tokens.size() > 1 && removed < 2) {
            tokens.remove(tokens.size() - 1);
            removed++;
        }
    }
}
