package br.com.economize.service.integration;

import br.com.economize.dto.analytics.IncomePatternResponse;
import br.com.economize.dto.integration.IntegrationShoppingResponses;
import br.com.economize.dto.wish.WishResponses;
import br.com.economize.service.wish.CommittedIncomeService;
import br.com.economize.service.wish.IncomePatternService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * O conselho de compra para a integração (V44).
 *
 * <p><b>Não calcula nada.</b> Junta duas respostas que o app já tem e que já
 * têm teste próprio: o padrão de renda com o melhor dia
 * ({@link IncomePatternService#analyze}) e o "quanto está livre" do próximo
 * salário ({@link CommittedIncomeService#overview}). Regra nova aqui seria um
 * segundo número para a mesma pergunta, e os dois sistemas discordando.
 *
 * <p><b>O ETag ignora {@code generatedAt}.</b> Ele é o resumo do CONTEÚDO do
 * conselho; a hora em que a resposta foi montada muda a cada chamada e, se
 * entrasse na conta, o {@code If-None-Match} nunca casaria.
 */
@Service
@RequiredArgsConstructor
public class IntegrationAdviceService {

    private final IncomePatternService incomePatternService;
    private final CommittedIncomeService committedIncomeService;
    private final ObjectMapper objectMapper;

    /** A resposta e o ETag dela, já entre aspas como o cabeçalho pede. */
    public record Result(IntegrationShoppingResponses.IntegrationAdvice advice, String etag) {
    }

    public Result advice(String email) {
        IncomePatternResponse pattern = incomePatternService.analyze(email);
        WishResponses.CommittedOverview committed = committedIncomeService.overview(email);

        IntegrationShoppingResponses.FreeAfterSalary free = new IntegrationShoppingResponses.FreeAfterSalary(
                committed.salaryKnown(),
                committed.salaryDate(),
                committed.daysUntilSalary(),
                cents(committed.expectedSalary()),
                cents(committed.committedBeforeSalary()),
                cents(committed.committedAfterSalary()),
                cents(committed.free()));

        return new Result(
                new IntegrationShoppingResponses.IntegrationAdvice(Instant.now(), pattern, free),
                etag(pattern, free));
    }

    private String etag(IncomePatternResponse pattern, IntegrationShoppingResponses.FreeAfterSalary free) {
        try {
            byte[] content = objectMapper.writeValueAsBytes(List.of(pattern, free));
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            // 128 bits bastam para distinguir versões de uma resposta
            return "\"" + digest.substring(0, 32) + "\"";
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Long cents(BigDecimal value) {
        return value == null ? null
                : value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }
}
