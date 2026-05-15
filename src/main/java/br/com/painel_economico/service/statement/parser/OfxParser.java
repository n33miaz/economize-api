package br.com.painel_economico.service.statement.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class OfxParser implements StatementParserStrategy {

    private static final Pattern STMTTRN = Pattern.compile("<STMTTRN>(.*?)</STMTTRN>", Pattern.DOTALL);
    private static final Pattern TRNTYPE = Pattern.compile("<TRNTYPE>([^<\\r\\n]+)");
    private static final Pattern DTPOSTED = Pattern.compile("<DTPOSTED>([^<\\r\\n\\[]+)");
    private static final Pattern TRNAMT = Pattern.compile("<TRNAMT>([^<\\r\\n]+)");
    private static final Pattern FITID = Pattern.compile("<FITID>([^<\\r\\n]+)");
    private static final Pattern MEMO = Pattern.compile("<MEMO>([^<\\r\\n]+)");

    @Override
    public StatementFormat format() {
        return StatementFormat.OFX;
    }

    @Override
    public List<ParsedTransaction> parse(InputStream input) {
        String content = readAll(input);
        List<ParsedTransaction> result = new ArrayList<>();
        Matcher matcher = STMTTRN.matcher(content);
        while (matcher.find()) {
            String block = matcher.group(1);
            String fitId = extract(FITID, block);
            String amount = extract(TRNAMT, block);
            if (fitId == null || amount == null) continue;
            result.add(ParsedTransaction.builder()
                    .externalId(fitId.trim())
                    .type(safeTrim(extract(TRNTYPE, block), "UNKNOWN"))
                    .amount(new BigDecimal(amount.trim()))
                    .description(safeTrim(extract(MEMO, block), ""))
                    .date(parseDate(extract(DTPOSTED, block)))
                    .build());
        }
        return result;
    }

    private String readAll(InputStream input) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler OFX", e);
        }
        return sb.toString();
    }

    private String extract(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private String safeTrim(String value, String fallback) {
        return value != null ? value.trim() : fallback;
    }

    private OffsetDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.length() < 8) return OffsetDateTime.now();
        try {
            String clean = dateStr.trim();
            String fixed = clean.length() >= 14 ? clean.substring(0, 14) : clean.substring(0, 8) + "000000";
            return LocalDateTime.parse(fixed, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    .atOffset(ZoneOffset.UTC);
        } catch (Exception e) {
            log.warn("Data OFX inválida '{}', usando agora", dateStr);
            return OffsetDateTime.now();
        }
    }
}
