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

    private static final Pattern LINE = Pattern.compile(
            "(\\d{2}/\\d{2}/\\d{4})\\s+(.+?)\\s+(-?R?\\$?\\s?\\d{1,3}(?:[.\\s]\\d{3})*(?:,\\d{2})?)");

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
        Matcher matcher = LINE.matcher(line);
        if (!matcher.find()) return null;
        String date = matcher.group(1);
        String desc = matcher.group(2).trim();
        String rawValue = matcher.group(3).replace("R$", "").replace(" ", "").trim();
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
}
