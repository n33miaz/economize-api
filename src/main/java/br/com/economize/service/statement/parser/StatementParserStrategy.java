package br.com.economize.service.statement.parser;

import java.io.InputStream;
import java.util.List;

public interface StatementParserStrategy {
    StatementFormat format();

    List<ParsedTransaction> parse(InputStream input);

    /**
     * O saldo que o arquivo declara, quando o formato tem onde declarar.
     *
     * <p>Padrão nulo de propósito: CSV, XLSX e TXT de banco são listas de
     * lançamento e não trazem saldo nenhum; obrigar cada um a responder isso
     * seria cerimônia para devolver {@code null}. Quem tem o dado — hoje o OFX,
     * pelo {@code <LEDGERBAL>} — sobrescreve.
     *
     * <p>Recebe o conteúdo de novo porque {@link #parse(InputStream)} já
     * consumiu o fluxo. Arquivo de extrato é pequeno e já está inteiro em
     * memória no serviço de importação; ler duas vezes custa menos que guardar
     * estado num parser que é um bean compartilhado.
     */
    default StatementBalance parseBalance(InputStream input) {
        return null;
    }
}
