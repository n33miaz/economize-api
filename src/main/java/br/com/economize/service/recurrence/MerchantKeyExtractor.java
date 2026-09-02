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
import java.util.regex.Matcher;
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

    /**
     * Resultado da extração: âncora conhecida OU tokens candidatos a entidade.
     * {@code transferLike} marca PIX/TED/transferência, cuja contraparte é
     * identificada de outro jeito (ver {@link #entityKey}).
     */
    public record Extraction(String anchor, List<String> tokens, RecurringSeries.Cadence cadenceHint,
                             boolean transferLike) {
    }

    // Descrição de transferência entre contas (PIX/TED): a contraparte que ela
    // nomeia é quase sempre uma pessoa, e pessoa não se identifica por um token
    private static final Set<String> TRANSFER_MARKERS = Set.of("pix", "ted", "transferencia");

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
    // societários). "compra" entrou pelo extrato real: "Compra TD", "Compra Meio
    // De Transporte" e "Compra cartão" caíam numa série só por causa do verbo.
    // "null" é literal mesmo — o Inter imprime "Cp :NNN-null Fulano" quando o
    // banco da contraparte não veio, e o token virava o "primeiro nome" dela.
    // "agencia" é o campo "Agência: N" que o Nubank imprime em todo PIX: cada
    // extrato dele importado dava meses a um token que também está no nome de
    // agências de restaurante/viagem, e o dominante dessas trocava de varredura
    // para varredura — a série antiga ficava órfã e nascia uma gêmea.
    private static final Set<String> STOPWORDS = Set.of(
            "pagamento", "pagto", "pgto", "recebido", "recebida", "enviado", "enviada",
            "efetuado", "efetuada", "convenio", "conta", "contas", "agencia", "debito",
            "credito", "referente", "ref", "mes", "para", "com", "sem", "por", "de", "do",
            "da", "dos", "das", "em", "no", "na", "nos", "nas", "ltda", "sa", "me", "mei",
            "eireli", "epp", "cia", "banco", "bco", "pix", "ted", "doc", "transf",
            "transferencia", "cobranca", "boleto", "tarifa", "bra", "compra", "null");

    // Nome do banco nunca é a entidade: no extrato do Inter ele acompanha o CDB
    // ("CDB Porquinho BANCO INTER SA"), o cashback do plano de celular ("INTER
    // PRE 20GB") e o estorno — em 25 de 25 meses, mais que qualquer outro token —
    // e por isso o token dominante elegia "inter" para tudo: 271 lançamentos de
    // quatro entidades diferentes numa única série INCOME. Em PIX o banco da
    // contraparte também aparece por extenso e diz de onde veio o dinheiro, não
    // de quem. A lista cobre os bancos que imprimem o próprio nome no descritivo.
    private static final Set<String> BANK_NAMES = Set.of(
            "inter", "intermedium", "nubank", "itau", "bradesco", "santander", "caixa",
            "sicoob", "sicredi", "safra", "btg", "picpay", "banrisul");

    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    /** Dois ou mais caracteres de máscara seguidos: o CPF/CNPJ da contraparte. */
    private static final Pattern MASKED_DOCUMENT = Pattern.compile("[•*]{2,}");

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
                return new Extraction(anchor, List.of(), null, false);
            }
        }
        boolean transferLike = false;
        for (String marker : TRANSFER_MARKERS) {
            if (lightTokens.contains(marker)) transferLike = true;
        }

        // Numa transferência, o documento MASCARADO da contraparte encerra a
        // identidade dela: o que vem depois é banco de destino, agência e conta.
        // Lista de nomes de banco não resolve isso — "MERCADO PAGO IP LTDA" não
        // é nome de banco conhecido, acompanha TODO PIX para aquele destino e
        // por isso vira o token mais persistente do histórico, mais persistente
        // que o próprio nome de quem recebe. A máscara é o corte estrutural
        String base = transferLike ? beforeMaskedDocument(description) : description;
        List<String> tokens = splitTokens(DescriptionNormalizer.normalize(base));
        // Formato em que a máscara vem antes do nome deixaria a cabeça sem
        // token nenhum: aí vale a descrição inteira, como era antes
        if (tokens.isEmpty() && !base.equals(description)) {
            tokens = splitTokens(DescriptionNormalizer.normalize(description));
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
            if (STOPWORDS.contains(token) || BANK_NAMES.contains(token)) continue;
            if (UNIT_TOKEN.matcher(token).matches()) continue;
            // números soltos são agência/documento/quantidade — variam por cobrança
            if (DIGITS_ONLY.matcher(token).matches()) continue;
            // dedup preservando a ordem: "Pagamento ACME | ACME" é um token, não dois
            if (!entityTokens.contains(token)) entityTokens.add(token);
        }
        return new Extraction(null, entityTokens, hint, transferLike);
    }

    private static List<String> splitTokens(String normalized) {
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split(" ")) {
            if (!token.isBlank()) tokens.add(token);
        }
        return tokens;
    }

    /**
     * O trecho da descrição ANTES do documento mascarado da contraparte.
     *
     * <p>Os bancos mascaram CPF/CNPJ com pontos ou asteriscos
     * ({@code •••.123.456-••}); a partir dali o descritivo fala do destino, não
     * de quem recebeu. Sem máscara, devolve a descrição inteira.
     */
    private static String beforeMaskedDocument(String description) {
        Matcher matcher = MASKED_DOCUMENT.matcher(description);
        return matcher.find() ? description.substring(0, matcher.start()) : description;
    }

    /**
     * Chave de entidade a partir do token dominante eleito entre {@code candidates}.
     *
     * <p>Para compra, boleto e débito automático a chave é o token dominante
     * sozinho — é o que sobrevive quando o rótulo do estabelecimento muda. Para
     * PIX/TED a contraparte é uma PESSOA na maioria dos casos, e o token que mais
     * se repete no histórico é o SOBRENOME comum: medido no extrato real de dois
     * anos (EC-111), "silva" juntou 26 pessoas diferentes numa série de 33
     * lançamentos, "santos" 20 pessoas em 50, e o mesmo com oliveira, costa, lima,
     * araujo, barbosa, pereira — série nenhuma dessas existe. O nome que o banco
     * imprime no PIX é o cadastral e quase não muda, então a identidade passa a
     * ser primeiro nome + token dominante ("alice santos"): separa homônimos de
     * sobrenome, sobrevive à abreviação do meio do nome ("mirian a d cormino" e
     * "mirian aparecida damiao cormino" caem juntas) e casa o mesmo titular nos
     * dois bancos (o Nubank imprime o nome completo e o banco de destino, que é
     * stopword). Quando o dominante É o primeiro nome, o segundo mais persistente
     * completa a chave, senão duas "Maria" diferentes voltariam a colidir.
     *
     * <p>Custo assumido e medido: o estabelecimento pago ora por PIX, ora no
     * cartão, com primeiro token diferente do dominante ("doceria e confeitaria
     * X" × "X barueri bra") ganha uma série por meio de pagamento — 3 casos em 79
     * séries no extrato real, contra 14 séries de sobrenome que deixam de existir.
     */
    public static String entityKey(Extraction extraction, List<String> candidates,
                                   Map<String, Integer> monthCounts) {
        String dominant = dominantToken(candidates, monthCounts);
        if (dominant == null) return null;
        List<String> tokens = extraction.tokens();
        if (!extraction.transferLike() || tokens.size() < 2) return dominant;
        String first = tokens.get(0);
        if (!first.equals(dominant)) return first + " " + dominant;
        String second = dominantToken(tokens.subList(1, tokens.size()), monthCounts);
        return second != null ? first + " " + second : first;
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
        // mesma composição da detecção: um hint "Pix Maria Souza" vira "maria
        // souza", que é a chave que a varredura dará aos PIX reais dessa pessoa
        String entity = entityKey(extraction, extraction.tokens(), Map.of());
        String key = entity != null && !entity.isBlank()
                ? entity
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
    //
    // A cidade também é removida de onde mais aparecer: o OFX do Inter traz o
    // estabelecimento duas vezes (MEMO truncado + NAME reformatado, "... JOAO
    // VICTOR G BARUERI 1" + "Joao Victor G Barueri Bra"), e a cópia do meio
    // escapava do corte terminal. Como a cidade está em quase toda compra do
    // bairro, era ela o token mais persistente do histórico: "barueri" virou a
    // chave de 23 compras em 16 estabelecimentos diferentes.
    private static void stripTrailingCity(List<String> tokens) {
        if (tokens.isEmpty() || !"bra".equals(tokens.get(tokens.size() - 1))) return;
        tokens.remove(tokens.size() - 1);
        Set<String> city = new HashSet<>();
        int removed = 0;
        while (tokens.size() > 1 && removed < 2) {
            city.add(tokens.remove(tokens.size() - 1));
            removed++;
        }
        if (tokens.size() > 1) tokens.removeIf(city::contains);
    }
}
