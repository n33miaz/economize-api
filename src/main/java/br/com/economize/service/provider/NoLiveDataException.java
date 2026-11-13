package br.com.economize.service.provider;

/**
 * Nenhuma fonte viva respondeu. É o sinal com que os métodos {@code @Cacheable}
 * de mercado terminam quando não há dado novo — em vez de terminar vazios ou
 * com o snapshot.
 *
 * <p>
 * Por que erro e não vazio: no cache reativo do Spring (Caffeine assíncrono) o
 * erro não é guardado, e o vazio é — como nulo, pelo prazo inteiro do cache.
 * Terminar vazio, ou com o snapshot velho, deixava uma falha de segundos sendo
 * servida por até seis horas. Quem chama troca este erro pelo fallback
 * (snapshot/vazio) FORA do cache, e a próxima chamada tenta a rede de novo.
 *
 * <p>
 * Sem pilha: é fluxo esperado (provedor fora do ar), não defeito, e é criado a
 * cada falha.
 */
public class NoLiveDataException extends RuntimeException {

    public NoLiveDataException(String what) {
        super("Sem dado vivo: " + what, null, false, false);
    }
}
