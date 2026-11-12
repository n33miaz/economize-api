package br.com.economize.service.statement.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class TxtParser implements StatementParserStrategy {

    /*
     * Aceita exatamente as mesmas linhas da forma antiga,
     * "(data)\s+(.+?)\s+(valor)" com find(), mas sem a volta atrás que a fazia
     * explodir: com espaços sobrando na linha, os dois \s+ e a descrição
     * disputavam os mesmos brancos (custo cúbico no tamanho da corrida), e o
     * find() recomeçava em cada data da linha (custo quadrático).
     *
     * - Os brancos depois da data e antes do valor são possessivos: devolvê-los
     *   nunca mudava o resultado, só o tempo.
     * - A descrição termina sempre num caractere não branco; terminar no meio
     *   de uma corrida de brancos dava o mesmo valor, tentado de novo.
     * - O segundo ramo guarda o caso raro "data, 3+ brancos e valor", em que a
     *   forma antiga devolvia um branco como descrição (vazia após o trim).
     */
    private static final Pattern LINE = Pattern.compile(
            "(\\d{2}/\\d{2}/\\d{4})(?:\\s++(.+?)(?<=\\S)|\\s(\\s))\\s++"
                    + "(-?R?\\$?\\s?\\d{1,3}(?:[.\\s]\\d{3})*(?:,\\d{2})?)");

    /** Onde uma tentativa pode começar: a data seguida de branco. */
    private static final Pattern INICIO = Pattern.compile("\\d{2}/\\d{2}/\\d{4}\\s");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Override
    public StatementFormat format() {
        return StatementFormat.TXT;
    }

    @Override
    public List<ParsedTransaction> parse(InputStream input) {
        List<ParsedTransaction> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int seq = 0;
            while ((line = reader.readLine()) != null) {
                ParsedTransaction tx = parseLine(line, seq++);
                if (tx != null) result.add(tx);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler TXT", e);
        }
        return result;
    }

    private ParsedTransaction parseLine(String line, int seq) {
        Matcher matcher = primeiraOcorrencia(line);
        if (matcher == null) return null;
        String date = matcher.group(1);
        String desc = matcher.group(2) != null ? matcher.group(2).trim() : matcher.group(3).trim();
        String rawValue = matcher.group(4).replace("R$", "").replace(" ", "").trim();
        boolean negative = rawValue.startsWith("-");
        if (negative) rawValue = rawValue.substring(1);
        BigDecimal value;
        try {
            value = new BigDecimal(rawValue.replace(".", "").replace(",", "."));
            if (negative) value = value.negate();
        } catch (NumberFormatException e) {
            return null;
        }
        OffsetDateTime parsedDate;
        try {
            parsedDate = LocalDate.parse(date, DATE).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
        return ParsedTransaction.builder()
                .externalId("TXT-" + seq + "-" + date)
                .type(value.signum() >= 0 ? "CREDIT" : "DEBIT")
                .amount(value)
                .description(desc)
                .date(parsedDate)
                .build();
    }

    /**
     * O mesmo que {@code LINE.matcher(line).find()}, em tempo linear.
     *
     * <p>Se a tentativa numa data falha, nenhuma data adiante pode dar certo:
     * a descrição da primeira alcançaria qualquer "branco + valor" que servisse
     * à segunda. A única coisa que corta esse alcance é um separador de linha
     * que o {@code readLine} não quebra (U+0085, U+2028, U+2029), porque o
     * {@code .} não o atravessa; por isso a busca recomeça depois dele, e não na
     * data seguinte. Sem isso o find() refazia a varredura a partir de cada
     * data, e uma linha de 5 MB com datas coladas levava minutos.
     */
    private static Matcher primeiraOcorrencia(String line) {
        Matcher inicio = INICIO.matcher(line);
        Matcher matcher = LINE.matcher(line);
        int de = 0;
        while (de < line.length() && inicio.find(de)) {
            matcher.region(inicio.start(), line.length());
            if (matcher.lookingAt()) return matcher;
            int quebra = proximaQuebra(line, inicio.start());
            if (quebra < 0) return null;
            de = quebra + 1;
        }
        return null;
    }

    private static int proximaQuebra(String line, int de) {
        for (int i = de; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\u0085' || c == ' ' || c == ' ') return i;
        }
        return -1;
    }
}
