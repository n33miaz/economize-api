package br.com.economize.service;

/**
 * Um valor que veio do usuário, pronto para entrar numa linha de log.
 *
 * <p><b>O defeito que isto fecha.</b> Código de ticker, de moeda e chave de
 * snapshot chegam pela URL e iam crus para o log. Um valor com quebra de
 * linha no meio forja uma linha de log inteira — {@code PETR4\n2026-09-11
 * ERROR Banco fora do ar} aparece no arquivo como duas linhas, e a segunda
 * parece nossa. É o que o CodeQL chama de <i>log injection</i>, e sobravam
 * dez pontos assim depois da troca e-mail → id.
 *
 * <p><b>Por que sanear no log, e não recusar na entrada.</b> Recusar seria o
 * certo para um ticker — mas cada endpoint tem a própria validação, e a
 * regra que vale para {@code PETR4} não vale para uma chave de snapshot ou um
 * par de moedas. Aqui é a última linha de defesa: seja o que for que passou,
 * ele entra no log numa linha só e com tamanho que cabe.
 *
 * <p><b>O que ele NÃO faz:</b> não mascara nem esconde. Dado pessoal não se
 * saneia — não se loga. Para isso existe {@link #email(String)}, que é outra
 * pergunta.
 */
public final class LogSafe {

    /** Um ticker cabe em 20; uma chave de snapshot em 60. Acima disso é lixo. */
    private static final int TETO = 80;

    private LogSafe() {
    }

    /**
     * O valor numa linha só, sem caractere de controle, com teto de tamanho.
     *
     * <p>Nulo continua nulo (o log escreve "null", que é informação); vazio
     * continua vazio.
     */
    public static String value(String bruto) {
        if (bruto == null) return null;
        // Tudo que não é imprimível vira espaço: quebra de linha, tab, retorno
        // de carro e os controles ASCII que um terminal interpreta
        String limpo = bruto.replaceAll("[\\p{Cntrl}]", " ");
        if (limpo.length() > TETO) {
            return limpo.substring(0, TETO - 1) + "…";
        }
        return limpo;
    }

    /**
     * Um e-mail para o log: só o domínio, e a primeira letra da conta.
     *
     * <p>{@code n***@gmail.com} responde "para quem foi" o suficiente para
     * depurar — e não é dado pessoal. O e-mail inteiro no log de produção é
     * PII gravada em texto plano, num arquivo que vive em outro lugar e por
     * outro tempo que o banco.
     */
    public static String email(String bruto) {
        if (bruto == null || bruto.isBlank()) return null;
        String limpo = value(bruto.trim());
        int arroba = limpo.indexOf('@');
        if (arroba <= 0) return "***";
        return limpo.charAt(0) + "***" + limpo.substring(arroba);
    }
}
