package br.com.painel_economico.service.statement.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
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

@Slf4j
@Component
public class CsvParser implements StatementParserStrategy {

    private static final DateTimeFormatter[] FORMATS = new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy")
    };

    @Override
    public StatementFormat format() {
        return StatementFormat.CSV;
    }

    @Override
    public List<ParsedTransaction> parse(InputStream input) {
        List<ParsedTransaction> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .setIgnoreSurroundingSpaces(true)
                    .setDelimiter(detectDelimiter(reader))
                    .build();
            try (CSVParser parser = new CSVParser(reader, format)) {
                int seq = 0;
                for (CSVRecord rec : parser) {
                    ParsedTransaction tx = mapRow(rec, seq++);
                    if (tx != null) result.add(tx);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler CSV", e);
        }
        return result;
    }

    private char detectDelimiter(BufferedReader reader) throws IOException {
        reader.mark(2048);
        String first = reader.readLine();
        reader.reset();
        if (first == null) return ',';
        long commas = first.chars().filter(c -> c == ',').count();
        long semicolons = first.chars().filter(c -> c == ';').count();
        return semicolons > commas ? ';' : ',';
    }

    private ParsedTransaction mapRow(CSVRecord rec, int seq) {
        String date = firstOf(rec, "Data", "data", "Date", "date");
        String description = firstOf(rec, "Descrição", "Descricao", "Description", "Histórico", "Historico");
        String amount = firstOf(rec, "Valor", "Amount", "valor", "amount");
        if (date == null || amount == null) return null;
        BigDecimal value;
        try {
            value = new BigDecimal(amount.replace("R$", "").replace(".", "").replace(",", ".").trim());
        } catch (Exception e) {
            log.warn("Valor inválido '{}', ignorando linha", amount);
            return null;
        }
        return ParsedTransaction.builder()
                .externalId("CSV-" + seq + "-" + date)
                .type(value.signum() >= 0 ? "CREDIT" : "DEBIT")
                .amount(value)
                .description(description != null ? description : "")
                .date(parseDate(date))
                .build();
    }

    private String firstOf(CSVRecord rec, String... keys) {
        for (String key : keys) {
            if (rec.isMapped(key)) {
                String value = rec.get(key);
                if (value != null && !value.isBlank()) return value.trim();
            }
        }
        return null;
    }

    private OffsetDateTime parseDate(String raw) {
        for (DateTimeFormatter fmt : FORMATS) {
            try {
                return LocalDate.parse(raw, fmt).atStartOfDay().atOffset(ZoneOffset.UTC);
            } catch (Exception ignored) {
            }
        }
        log.warn("Data CSV inválida '{}'", raw);
        return OffsetDateTime.now();
    }
}
