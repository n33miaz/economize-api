package br.com.economize.service.event;

import br.com.economize.service.statement.parser.StatementFormat;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.UUID;

@Value
public class StatementImportedEvent implements DomainEvent {
    UUID userId;
    StatementFormat format;
    int transactionsImported;
    // permite a um consumidor assíncrono localizar as transações da importação
    UUID uploadId;
    /**
     * O dia mais antigo e o mais novo em que esta importação mexeu — linhas
     * novas de TODAS as fontes e linhas antigas que ganharam conta de origem.
     *
     * <p>Vão no evento, e não saem de uma consulta pelo {@link #uploadId},
     * porque a sincronização de várias conexões grava um upload por fonte e o
     * evento só carrega o principal: perguntar ao banco "quais as datas deste
     * upload" esqueceria as linhas das outras conexões.
     *
     * <p>Nulos quando quem publica não sabe — quem escuta trata como "o
     * extrato inteiro". Fora do JSON de propósito: o conversor do broker é um
     * ObjectMapper sem o módulo de datas, e só a faxina em processo lê isto.
     */
    @JsonIgnore
    OffsetDateTime earliestDate;
    @JsonIgnore
    OffsetDateTime latestDate;

    /**
     * Sem intervalo de datas — quem escuta varre o extrato inteiro.
     *
     * <p>É também por onde o JSON do broker volta a ser evento: com dois
     * construtores o Jackson não adivinha qual usar, então fica dito.
     */
    @JsonCreator
    public StatementImportedEvent(@JsonProperty("userId") UUID userId,
                                  @JsonProperty("format") StatementFormat format,
                                  @JsonProperty("transactionsImported") int transactionsImported,
                                  @JsonProperty("uploadId") UUID uploadId) {
        this(userId, format, transactionsImported, uploadId, null, null);
    }

    public StatementImportedEvent(UUID userId, StatementFormat format, int transactionsImported,
                                  UUID uploadId, OffsetDateTime earliestDate,
                                  OffsetDateTime latestDate) {
        this.userId = userId;
        this.format = format;
        this.transactionsImported = transactionsImported;
        this.uploadId = uploadId;
        this.earliestDate = earliestDate;
        this.latestDate = latestDate;
    }

    @Override
    public String type() {
        return "statement.imported";
    }
}
