package br.com.economize.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * O intervalo de datas que UMA importação trouxe ou alterou — e o recorte do
 * extrato que cada varredura precisa ler para tratar só isso.
 *
 * <p><b>Por que existe.</b> A faxina roda depois de toda importação, e cada
 * varredura carregava o histórico inteiro do usuário mesmo quando tinha entrado
 * uma linha: seis leituras de ~2.000 lançamentos num serviço de 512 MB. As
 * varreduras cuja regra é linha a linha, ou tem distância máxima entre as duas
 * pontas, só precisam do que entrou e do que está a essa distância.
 *
 * <p><b>A margem é da regra, não da janela.</b> Cada varredura pede o recorte
 * com a distância que a regra DELA admite (zero para as que olham uma linha de
 * cada vez, um dia para duplicatas). Por isso este tipo guarda só as duas
 * pontas do que foi importado.
 *
 * <p><b>Dia inteiro, em UTC, e com um dia de folga de cada lado.</b> As regras
 * comparam dia de calendário, não duração — o par de duplicata medido em
 * produção tem 24 h e 5 min entre as pontas e é "um dia de diferença". Cortar
 * no instante deixaria esse par de fora. A folga cobre a diferença entre o
 * fuso em que a linha volta do banco e o UTC destes limites. Ler um dia a mais
 * nunca muda o resultado: quem decide o par continua sendo a regra, em memória,
 * igual à varredura total; ler um dia a menos mudaria.
 */
public record HygieneWindow(OffsetDateTime earliest, OffsetDateTime latest) {

    /** Folga, em dias, somada à margem da regra de cada lado. */
    private static final long FOLGA_DIAS = 1;

    public HygieneWindow {
        if (earliest == null || latest == null) {
            throw new IllegalArgumentException("A janela precisa das duas pontas");
        }
        if (latest.isBefore(earliest)) {
            throw new IllegalArgumentException("A janela termina antes de começar");
        }
    }

    /**
     * A janela, ou nulo quando a importação não disse o intervalo. Nulo é
     * "varredura total" para quem recebe: sem saber o que entrou, o único
     * recorte seguro é nenhum.
     */
    public static HygieneWindow of(OffsetDateTime earliest, OffsetDateTime latest) {
        if (earliest == null || latest == null) return null;
        return new HygieneWindow(earliest, latest);
    }

    /** Início do recorte, inclusivo: o primeiro dia importado menos a margem. */
    public OffsetDateTime from(long marginDays) {
        return earliest.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()
                .minusDays(marginDays + FOLGA_DIAS)
                .atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /** Fim do recorte, EXCLUSIVO: a virada do dia seguinte ao último coberto. */
    public OffsetDateTime until(long marginDays) {
        return latest.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()
                .plusDays(marginDays + FOLGA_DIAS + 1)
                .atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
