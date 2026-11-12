package br.com.economize.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diz quando a faxina pós-importação ainda deve varrer o HISTÓRICO INTEIRO.
 *
 * <p>A faxina em janela só olha o que a importação trouxe. Isso é certo
 * enquanto o resto do histórico está assentado — e ele deixa de estar quando
 * uma faxina anterior falhou no meio, quando o usuário mudou de nome, entrou
 * numa família ou desfez uma marca à mão. Antes, a importação seguinte
 * consertava tudo isso sem ninguém pedir, porque varria tudo sempre. Para não
 * perder essa repescagem, a varredura total continua acontecendo, só que com
 * teto de frequência: a primeira importação do usuário depois de cada
 * intervalo (e depois de cada reinício do serviço) é total; as outras, em
 * janela. Uma faxina que falha apaga a marca, e a próxima volta a ser total.
 *
 * <p>Em memória de propósito: guardar "quando foi a última total" no banco
 * pediria coluna nova, e o pior que a memória faz ao se perder é rodar uma
 * varredura total a mais — exatamente o que sempre se fez.
 */
@Component
public class HygieneCadence {

    /** Presença da chave = houve varredura total bem-sucedida dentro do intervalo. */
    private final Cache<UUID, Boolean> sweptRecently;

    /** Quantas faxinas falharam desde que o serviço subiu. */
    private final AtomicLong failures = new AtomicLong();

    public HygieneCadence(
            @Value("${economize.hygiene.full-sweep-interval:PT24H}") Duration interval) {
        this.sweptRecently = Caffeine.newBuilder()
                .expireAfterWrite(interval)
                .maximumSize(50_000)
                .build();
    }

    /** A próxima faxina deste usuário precisa ser a total? */
    public boolean fullSweepDue(UUID userId) {
        return sweptRecently.getIfPresent(userId) == null;
    }

    /**
     * Senha para {@link #fullSweepDone(UUID, long)}, tirada ANTES de a
     * varredura total começar a ler.
     */
    public long begin() {
        return failures.get();
    }

    /**
     * Marca o histórico como assentado — a não ser que alguma faxina tenha
     * falhado enquanto esta total rodava. O caso: duas importações do mesmo
     * usuário quase juntas; a total lê o histórico, a outra grava linhas e a
     * faxina em janela dela falha. A total não viu essas linhas, então não
     * pode dar o histórico por limpo. O contador é global, não por usuário:
     * a falha de um cancela a marca de quem estava rodando ao mesmo tempo, e
     * o preço disso é só uma varredura total a mais.
     */
    public void fullSweepDone(UUID userId, long token) {
        if (failures.get() != token) return;
        sweptRecently.put(userId, Boolean.TRUE);
        // a falha pode ter chegado entre a conferência e a gravação
        if (failures.get() != token) sweptRecently.invalidate(userId);
    }

    /** Qualquer faxina que falha deixa o histórico em dúvida: a próxima é total. */
    public void failed(UUID userId) {
        failures.incrementAndGet();
        sweptRecently.invalidate(userId);
    }
}
