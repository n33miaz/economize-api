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

    /*
     * Os blocos e o saldo são achados com indexOf, não com regex: o antigo
     * "<STMTTRN>(.*?)</STMTTRN>" com find() recomeçava a varredura em cada
     * abertura sem fecho e ia até o fim do arquivo — 200 mil aberturas soltas
     * em 2 MB eram dezenas de bilhões de passos, prendendo a thread de CPU.
     */
    private static final String STMTTRN_ABRE = "<STMTTRN>";
    private static final String STMTTRN_FECHA = "</STMTTRN>";
    private static final Pattern TRNTYPE = Pattern.compile("<TRNTYPE>([^<\\r\\n]+)");
    private static final Pattern DTPOSTED = Pattern.compile("<DTPOSTED>([^<\\r\\n\\[]+)");
    private static final Pattern TRNAMT = Pattern.compile("<TRNAMT>([^<\\r\\n]+)");
    private static final Pattern FITID = Pattern.compile("<FITID>([^<\\r\\n]+)");
    private static final Pattern MEMO = Pattern.compile("<MEMO>([^<\\r\\n]+)");
    private static final Pattern NAME = Pattern.compile("<NAME>([^<\\r\\n]+)");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /**
     * O bloco de saldo. {@code LEDGERBAL} é o saldo do LIVRO — o que o banco
     * diz que existe na conta no momento da leitura. O {@code AVAILBAL}
     * (disponível), que costuma vir logo depois, fica de fora de propósito:
     * ele soma limite de cheque especial, e limite não é dinheiro do usuário.
     *
     * <p>Em fatura de cartão (`CCSTMTRS`) o mesmo bloco traz o valor DEVIDO, e
     * é assim que o resto do sistema já trata `reportedBalance` de cartão.
     *
     * <p>O fecho é tolerante porque OFX 1.x é SGML: muitos bancos não fecham
     * as tags. Sem os limites alternativos, o bloco engoliria o arquivo
     * inteiro e o `BALAMT` capturado poderia ser o do `AVAILBAL`.
     */
    private static final String LEDGERBAL_ABRE = "<LEDGERBAL>";
    private static final List<String> LEDGERBAL_FECHOS =
            List.of("</LEDGERBAL>", "<AVAILBAL>", "</STMTRS>", "</CCSTMTRS>");
    private static final Pattern BALAMT = Pattern.compile("<BALAMT>([^<\\r\\n]+)");
    private static final Pattern DTASOF = Pattern.compile("<DTASOF>([^<\\r\\n\\[]+)");

    @Override
    public StatementFormat format() {
        return StatementFormat.OFX;
    }

    @Override
    public List<ParsedTransaction> parse(InputStream input) {
        String content = readAll(input);
        List<ParsedTransaction> result = new ArrayList<>();
        int ignoradasSemData = 0;
        int pos = 0;
        while (true) {
            // Mesmo resultado da regex antiga, em uma passada só: se a abertura
            // atual não tem fecho adiante, nenhuma abertura posterior terá, e
            // a leitura para em vez de varrer o resto do arquivo outra vez.
            int abre = content.indexOf(STMTTRN_ABRE, pos);
            if (abre < 0) break;
            int inicio = abre + STMTTRN_ABRE.length();
            int fecha = content.indexOf(STMTTRN_FECHA, inicio);
            if (fecha < 0) break;
            pos = fecha + STMTTRN_FECHA.length();
            String block = content.substring(inicio, fecha);
            String fitId = extract(FITID, block);
            String amountRaw = extract(TRNAMT, block);
            if (fitId == null || amountRaw == null) continue;
            BigDecimal amount = new BigDecimal(amountRaw.trim());
            OffsetDateTime date = parseDate(extract(DTPOSTED, block));
            if (date == null) {
                ignoradasSemData++;
                continue;
            }
            result.add(ParsedTransaction.builder()
                    .externalId(fitId.trim())
                    .type(resolveType(extract(TRNTYPE, block), amount))
                    .amount(amount)
                    .description(buildDescription(extract(MEMO, block), extract(NAME, block)))
                    .date(date)
                    .build());
        }
        if (ignoradasSemData > 0) {
            // A data participa da deduplicação e dos ciclos; sem ela, datar a
            // compra como hoje mudaria o histórico em vez de apenas rejeitá-la.
            log.warn("OFX descartou {} lançamento(s) sem DTPOSTED válido", ignoradasSemData);
        }
        if (result.isEmpty() && ignoradasSemData > 0) {
            // Sem data a transação não pode ser deduplicada nem entrar no
            // ciclo certo; dizer que não havia transação esconderia o defeito
            // real do arquivo e convidaria a pessoa a tentar importá-lo de novo.
            throw new IllegalArgumentException("As datas das transações do arquivo são inválidas; nenhuma pôde ser importada");
        }
        return result;
    }

    /**
     * O saldo declarado no arquivo (EC: "3.021,06 não existe", 15/09/2026).
     *
     * <p>Sempre esteve aqui e era descartado: o parser lia as transações e
     * ignorava o bloco de saldo, então o app só sabia somar movimento. A
     * explicação longa — e o estrago que essa soma fazia na Home e na
     * Perspectiva de saldo — está em {@link StatementBalance}.
     *
     * <p>Sem bloco, sem valor ou com valor ilegível, devolve nulo: nulo aqui
     * significa "este arquivo não informa saldo", e quem chama tem de saber
     * lidar com isso sem inventar um número.
     */
    @Override
    public StatementBalance parseBalance(InputStream input) {
        String content = readAll(input);
        String bloco = blocoDeSaldo(content);
        if (bloco == null) return null;
        String valor = extract(BALAMT, bloco);
        if (valor == null) return null;
        try {
            BigDecimal amount = new BigDecimal(valor.trim());
            String quando = extract(DTASOF, bloco);
            OffsetDateTime asOf = parseDate(quando);
            if (asOf == null) {
                // Saldo sem data não pode disputar com saldos datados: tratá-lo
                // como recém-lido inventaria a informação que o OFX não trouxe.
                log.warn("OFX descartou saldo sem DTASOF válido");
                return null;
            }
            return new StatementBalance(amount, asOf);
        } catch (NumberFormatException e) {
            log.warn("Saldo OFX ilegível '{}', seguindo sem saldo", valor);
            return null;
        }
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

    /**
     * O conteúdo do primeiro {@code <LEDGERBAL>} até o fecho mais próximo
     * entre os aceitos (ver {@link #LEDGERBAL_FECHOS}), ou nulo.
     *
     * <p>Equivale à regex lazy que estava aqui, sem o custo quadrático dela:
     * se a primeira abertura não tem fecho adiante, as seguintes também não
     * têm, então não há por que tentar cada uma varrendo o arquivo até o fim.
     */
    private String blocoDeSaldo(String content) {
        int abre = content.indexOf(LEDGERBAL_ABRE);
        if (abre < 0) return null;
        int inicio = abre + LEDGERBAL_ABRE.length();
        int fim = -1;
        for (String fecho : LEDGERBAL_FECHOS) {
            int i = content.indexOf(fecho, inicio);
            if (i >= 0 && (fim < 0 || i < fim)) fim = i;
        }
        return fim < 0 ? null : content.substring(inicio, fim);
    }

    private String extract(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private OffsetDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().length() < 8) return null;
        try {
            String clean = dateStr.trim();
            String fixed = clean.length() >= 14 ? clean.substring(0, 14) : clean.substring(0, 8) + "000000";
            return LocalDateTime.parse(fixed, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    .atOffset(ZoneOffset.UTC);
        } catch (Exception e) {
            // Não registra o valor do OFX: data pode carregar conteúdo fora do
            // padrão e o contador acima já permite diagnosticar o arquivo.
            return null;
        }
    }
}
