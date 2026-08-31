package br.com.economize.service.statement.category;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Distingue os tipos de dívida escondidos na descrição do extrato (EC-139).
 *
 * <p>Hoje o app trata tudo como despesa, e por isso soma maçã com laranja: a
 * parcela de um financiamento, a entrada de um empréstimo e a fatura no
 * rotativo têm significados opostos para "quanto sobrou". Sem essa distinção o
 * número mente.
 *
 * <table>
 * <caption>O que muda em cada tipo</caption>
 * <tr><td><b>Financiamento</b></td><td>o bem já é seu, a dívida também; a parcela tem juros e amortização</td></tr>
 * <tr><td><b>Parcelamento</b></td><td>sem juros aparentes, mas compromete meses futuros</td></tr>
 * <tr><td><b>Consórcio</b></td><td>não é compra até a contemplação — é poupança forçada com taxa</td></tr>
 * <tr><td><b>Empréstimo</b></td><td>a ENTRADA não é receita, e a saída que vem depois não é gasto novo</td></tr>
 * <tr><td><b>Rotativo</b></td><td>o mais caro do país e o que mais precisa de alarme</td></tr>
 * </table>
 */
public final class DebtClassifier {

    private DebtClassifier() {
    }

    public enum DebtKind {
        /** Veículo, imóvel, CDC: parcela com juros e amortização. */
        FINANCING,
        /** Compra dividida em vezes — compromete meses futuros. */
        INSTALLMENT,
        /** Poupança forçada com taxa; só vira compra na contemplação. */
        CONSORTIUM,
        /** Dinheiro que entrou e não é receita. */
        LOAN,
        /** Rotativo e parcelamento de fatura: o alarme mais caro. */
        REVOLVING,
        /** Nada disso — despesa comum. */
        NONE
    }

    /**
     * @param kind        o tipo identificado
     * @param installment número da parcela, quando a descrição informa
     * @param total       total de parcelas, quando a descrição informa
     */
    public record DebtSignal(DebtKind kind, Integer installment, Integer total) {

        public static final DebtSignal NONE = new DebtSignal(DebtKind.NONE, null, null);

        public boolean isDebt() {
            return kind != DebtKind.NONE;
        }

        /** Quantas ainda faltam, quando a descrição diz onde estamos. */
        public Integer remaining() {
            if (installment == null || total == null) return null;
            return Math.max(0, total - installment);
        }
    }

    // A ordem de teste É a regra: "parcelamento de fatura" é ROTATIVO, não
    // parcelamento de compra, e "adiantamento salarial" não é empréstimo
    private static final List<String> REVOLVING = List.of(
            "rotativo", "credito rotativo", "juros rotativo", "parcelamento de fatura",
            "parcelamento fatura", "pagamento minimo", "encargos de fatura",
            "refinanciamento de fatura");

    private static final List<String> CONSORTIUM = List.of("consorcio");

    private static final List<String> FINANCING = List.of(
            "financiamento", "financ ", "fin veiculo", "fin imobiliario", "fin imob",
            "credito imobiliario", "cred imobiliario", "leasing", "arrendamento",
            "cdc ", "credito direto");

    private static final List<String> LOAN = List.of(
            "emprestimo", "credito pessoal", "cred pessoal", "consignado",
            "capital de giro", "antecipacao fgts", "antecipacao saque aniversario",
            "cheque especial");

    private static final List<String> INSTALLMENT = List.of(
            "parcela", "parcelamento", "parc ", "prestacao");

    /**
     * Padrão N/M da parcela.
     *
     * <p><b>Cuidado que define o método:</b> em extrato brasileiro "03/12" é
     * quase sempre <i>data</i>, não parcela. Aceitar o padrão solto marcaria
     * toda compra de dezembro como parcelada. Por isso ele só vale quando
     * acompanhado de uma palavra de parcela — ou quando o total passa de 12,
     * que nenhum mês alcança.
     */
    private static final Pattern INSTALLMENT_PATTERN =
            Pattern.compile("(?<![\\d/])(\\d{1,2})\\s*/\\s*(\\d{1,2})(?![\\d/])");

    /** Teto de parcelas que se vê na vida real; acima disso é outro número. */
    private static final int MAX_INSTALLMENTS = 96;

    public static DebtSignal classify(String description) {
        // Dobra própria, e NÃO o DescriptionNormalizer: aquele apaga datas,
        // padrões de parcela e a própria barra (é o que ele existe para fazer —
        // reduzir a descrição à identidade do estabelecimento). Usá-lo aqui
        // entregaria um texto sem nenhum "3/12" para encontrar
        String text = fold(description);
        if (text.isBlank()) return DebtSignal.NONE;
        // as bordas deixam os palpites com espaço ("parc ", "cdc ") casarem
        // também quando a palavra é o rótulo inteiro
        String padded = " " + text + " ";

        boolean hasInstallmentWord = containsAny(padded, INSTALLMENT);
        Integer[] parts = extractInstallment(text, hasInstallmentWord);

        // Rotativo primeiro: "parcelamento de fatura" tem a palavra parcela e
        // NÃO é parcelamento de compra — é a dívida mais cara do país
        if (containsAny(padded, REVOLVING)) {
            return new DebtSignal(DebtKind.REVOLVING, parts[0], parts[1]);
        }
        if (containsAny(padded, CONSORTIUM)) {
            return new DebtSignal(DebtKind.CONSORTIUM, parts[0], parts[1]);
        }
        // Financiamento antes de parcela: "parcela do financiamento" é
        // financiamento, e o tipo mais específico é o que informa
        if (containsAny(padded, FINANCING)) {
            return new DebtSignal(DebtKind.FINANCING, parts[0], parts[1]);
        }
        if (containsAny(padded, LOAN)) {
            return new DebtSignal(DebtKind.LOAN, parts[0], parts[1]);
        }
        if (hasInstallmentWord || parts[0] != null) {
            return new DebtSignal(DebtKind.INSTALLMENT, parts[0], parts[1]);
        }
        return DebtSignal.NONE;
    }

    /**
     * Devolve {@code [parcela, total]}, ou dois nulos quando o padrão não é
     * confiável. Ver a nota do {@link #INSTALLMENT_PATTERN}.
     */
    private static Integer[] extractInstallment(String text, boolean hasInstallmentWord) {
        Matcher matcher = INSTALLMENT_PATTERN.matcher(text);
        while (matcher.find()) {
            int atual = Integer.parseInt(matcher.group(1));
            int total = Integer.parseInt(matcher.group(2));
            if (atual < 1 || total < 2 || atual > total || total > MAX_INSTALLMENTS) continue;
            // sem palavra de parcela, só um total acima de 12 é inequívoco —
            // nenhum mês vai além disso
            if (!hasInstallmentWord && total <= 12) continue;
            return new Integer[]{atual, total};
        }
        return new Integer[]{null, null};
    }

    /** Minúsculas e sem acento, preservando dígitos e a barra da parcela. */
    private static String fold(String value) {
        if (value == null) return "";
        String stripped = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean containsAny(String text, List<String> hints) {
        for (String hint : hints) {
            if (text.contains(hint)) return true;
        }
        return false;
    }
}
