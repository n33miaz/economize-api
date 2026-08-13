package br.com.economize.service.statement.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final Pattern NAME = Pattern.compile("<NAME>([^<\\r\\n]+)");
    private static final Pattern SPACES = Pattern.compile("\\s+");

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
            String amountRaw = extract(TRNAMT, block);
            if (fitId == null || amountRaw == null) continue;
            BigDecimal amount = new BigDecimal(amountRaw.trim());
            result.add(ParsedTransaction.builder()
                    .externalId(fitId.trim())
                    .type(resolveType(extract(TRNTYPE, block), amount))
                    .amount(amount)
                    .description(buildDescription(extract(MEMO, block), extract(NAME, block)))
                    .date(parseDate(extract(DTPOSTED, block)))
                    .build());
        }
        return result;
    }

    /**
     * Bancos usam TRNTYPE fora do par CREDIT/DEBIT (o Inter marca débitos como
     * PAYMENT) — o sinal do valor é a fonte confiável; o TRNTYPE só desempata
     * o caso raro de valor zero.
     */
    private String resolveType(String trnType, BigDecimal amount) {
        if (amount.signum() < 0) return "DEBIT";
        if (amount.signum() > 0) return "CREDIT";
        String type = trnType != null ? trnType.trim().toUpperCase(Locale.ROOT) : "";
        return "DEBIT".equals(type) || "PAYMENT".equals(type) ? "DEBIT" : "CREDIT";
    }

    /**
     * MEMO carrega a operação e NAME o estabelecimento/contraparte (Inter);
     * juntos formam a mesma identidade que o CSV do banco produz. NAME só é
     * anexado quando o MEMO ainda não o contém (comparação com espaços
     * colapsados — o Inter alinha o NAME com colunas de espaços).
     */
    private String buildDescription(String memo, String name) {
        String memoTrim = memo != null ? memo.trim() : "";
        String nameTrim = name != null ? SPACES.matcher(name.trim()).replaceAll(" ") : "";
        if (nameTrim.isEmpty()) return memoTrim;
        if (memoTrim.isEmpty()) return nameTrim;
        String memoFlat = SPACES.matcher(memoTrim).replaceAll(" ").toLowerCase(Locale.ROOT);
        if (memoFlat.contains(nameTrim.toLowerCase(Locale.ROOT))) return memoTrim;
        return memoTrim + " " + nameTrim;
    }

    private String readAll(InputStream input) {
        try {
            byte[] bytes = input.readAllBytes();
            // o cabeçalho OFX declara o charset em ASCII puro — dá para sniffar
            // nele mesmo; Inter exporta CHARSET:1252, Nubank UTF-8
            String header = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.US_ASCII);
            Charset charset = header.contains("CHARSET:1252")
                    ? Charset.forName("windows-1252")
                    : StandardCharsets.UTF_8;
            return new String(bytes, charset);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler OFX", e);
        }
    }

    private String extract(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
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
