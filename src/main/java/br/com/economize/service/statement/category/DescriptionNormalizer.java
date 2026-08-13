package br.com.economize.service.statement.category;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reduz a descrição crua do extrato à identidade do estabelecimento, para que
 * "COMPRA CARTAO 12/07 IFOOD *REST 4321" e "IFOOD REST" caiam na mesma chave.
 * É essa chave que as regras aprendidas usam como padrão de matching.
 */
public final class DescriptionNormalizer {

    private DescriptionNormalizer() {
    }

    public static final int MAX_LENGTH = 160;

    // Jargões operacionais dos bancos (Inter, Nubank, Mercado Pago, etc.) que não
    // identificam o estabelecimento. A ordem de remoção é do maior para o menor
    // (garantida no fim da lista, não à mão) para que "compra no cartao de credito"
    // saia inteiro antes que "compra no cartao" o parta ao meio
    private static final List<String> DECLARED_NOISE = List.of(
            "compra no cartao de credito", "compra com cartao de credito",
            "compra no cartao de debito", "compra com cartao de debito",
            "pagamento de fatura de cartao", "compra no cartao", "compra com cartao",
            "compra cartao credito", "compra cartao debito", "compra no debito",
            "compra no credito", "compra cartao", "compra debito", "compra credito",
            "cartao de credito", "cartao de debito", "debito automatico",
            "pagamento de fatura", "pagamento fatura", "pagamento efetuado",
            "pagamento recebido", "pagamento de boleto", "pagamento boleto",
            // variantes reais do Nubank/Inter observadas em extratos de verdade
            "transferencia recebida pelo pix", "transferencia enviada pelo pix",
            "pix enviado devolvido", "pix recebido devolvido",
            "pix enviado para", "pix recebido de", "pix enviado", "pix recebido",
            "pix qr code", "pelo pix", "transferencia enviada para",
            "transferencia recebida de", "transferencia enviada",
            "transferencia recebida", "ted enviada",
            "ted recebida", "doc enviado", "doc recebido", "compra aprovada",
            "compra realizada", "compra online",
            // prefixos de adquirente/enriquecimento (Inter) que não identificam nada
            "no estabelecimento", "nome fantasia", "cp :"
    );

    private static final List<String> NOISE_PHRASES = DECLARED_NOISE.stream()
            .sorted(java.util.Comparator.comparingInt(String::length).reversed())
            .toList();

    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    // dd/mm, dd/mm/aa(aa) e marcadores de parcela ("parc 3/12", "03/12x")
    private static final Pattern DATE_LIKE = Pattern.compile("\\b\\d{1,2}/\\d{1,2}(/\\d{2,4})?\\b");
    private static final Pattern INSTALLMENT = Pattern.compile("\\bparc(ela)?\\s*\\d+\\s*/\\s*\\d+\\b");
    // sequências longas de dígitos = número de documento/cartão/autorização
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{4,}");
    // inclui os marcadores unicode que bancos usam para mascarar CPF (•) e travessões
    private static final Pattern SYMBOLS = Pattern.compile("[*#|_\\-.,:;!?()\\[\\]{}'\"/\\\\•·–—]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    public static String normalize(String description) {
        if (description == null || description.isBlank()) return "";

        String base = stripAccents(description.toLowerCase(Locale.ROOT));

        String cleaned = base;
        for (String phrase : NOISE_PHRASES) {
            cleaned = cleaned.replace(phrase, " ");
        }
        cleaned = INSTALLMENT.matcher(cleaned).replaceAll(" ");
        cleaned = DATE_LIKE.matcher(cleaned).replaceAll(" ");
        cleaned = LONG_DIGITS.matcher(cleaned).replaceAll(" ");
        cleaned = SYMBOLS.matcher(cleaned).replaceAll(" ");
        cleaned = SPACES.matcher(cleaned).replaceAll(" ").trim();

        // Se a limpeza engoliu tudo (ex.: descrição era só "PIX ENVIADO"), a versão
        // minúscula sem acentos ainda serve de chave — melhor que padrão vazio
        if (cleaned.isBlank()) {
            cleaned = SPACES.matcher(SYMBOLS.matcher(base).replaceAll(" ")).replaceAll(" ").trim();
        }

        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH).trim() : cleaned;
    }

    private static String stripAccents(String value) {
        return ACCENTS.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("");
    }
}
