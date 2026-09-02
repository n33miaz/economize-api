package br.com.economize.service.statement.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
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
            return decode(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler OFX", e);
        }
    }

    /**
     * O cabeçalho OFX declara o charset em ASCII puro, então dá para sniffar
     * nele mesmo — mas o cabeçalho MENTE. Medido com o extrato real de dois anos
     * do Inter (EC-111): declara {@code ENCODING:USASCII} + {@code CHARSET:1252}
     * e grava os bytes em UTF-8 ({@code C3 A7 C3 A3} em "Aplicação"). Honrar a
     * declaração produzia "AplicaÃ§Ã£o"/"CrÃ©dito"/"cartÃ£o" em 54 lançamentos,
     * e o lixo descia para a descrição normalizada, para a chave da série
     * recorrente ("cra©dito") e para o nome exibido.
     *
     * <p>Os bytes valem mais que a etiqueta: se o conteúdo decodifica como UTF-8
     * ESTRITO e contém ao menos uma sequência multibyte, é UTF-8 — texto legítimo
     * em 1252 com acento ("ç" = {@code E7}, "ã" = {@code E3}) não sobrevive à
     * decodificação estrita, porque esses bytes soltos são inválidos em UTF-8,
     * então não há como um extrato 1252 de verdade cair aqui por engano. Sem
     * multibyte nenhum o conteúdo é ASCII puro e qualquer charset serve. Só
     * quando a decodificação estrita falha é que a declaração do cabeçalho
     * decide, como antes (Nubank declara UTF-8 e cumpre).
     */
    static String decode(byte[] bytes) {
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            // menos chars que bytes = houve sequência multibyte válida
            if (utf8.length() < bytes.length) return utf8;
        } catch (CharacterCodingException notUtf8) {
            // segue para o charset declarado
        }
        String header = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.US_ASCII);
        Charset charset = header.contains("CHARSET:1252")
                ? Charset.forName("windows-1252")
                : StandardCharsets.UTF_8;
        return new String(bytes, charset);
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
