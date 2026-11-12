package br.com.economize.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * O relógio de quem pergunta "que dia é hoje" numa regra financeira.
 *
 * <p>O servidor roda em UTC e as pessoas estão no Brasil: das 21h à meia-noite
 * de lá o servidor já virou o dia. Lido em UTC, o "hoje" abria o mês seguinte,
 * fechava a fatura e vencia a parcela três horas antes da hora.
 *
 * <p>Só o HOJE vem daqui. O dia de um lançamento continua sendo o dia UTC do
 * instante gravado, porque é assim que os parsers e o conector guardam a data
 * do extrato (meia-noite UTC do dia que o banco informou); a data local obtida
 * deste relógio é comparada com esse dia sem conversão.
 */
@Configuration
public class FinancialClockConfig {

    @Bean
    public Clock financialClock(
            @Value("${economize.finance.zone:America/Sao_Paulo}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
