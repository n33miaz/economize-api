package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A compra que foi estornada para de contar — sem sumir do extrato.
 *
 * <p><b>O defeito que isto resolve.</b> Quem gastou R$ 4,00 e recebeu R$ 4,00
 * de volta não gastou nada. Hoje o app conta os dois: R$ 4,00 de despesa e
 * R$ 4,00 de receita. Os dois totais ficam inflados na mesma medida, o saldo
 * fecha (por isso ninguém percebe), e a análise de gastos mente. É a
 * reclamação nº 2 do concorrente, medida nas avaliações dele — e nós tínhamos
 * o mesmo buraco.
 *
 * <p><b>A regra veio dos extratos reais</b>, lidos em 09/09/2026. O extrato do
 * Inter de 12/08/2024 a 11/08/2026 tem <b>nove</b> lançamentos com histórico
 * "Estorno":
 *
 * <table><caption>Os nove</caption>
 *   <tr><td>06/04/2026</td><td>+1,20</td><td>casa com Aplicação −1,20 do mesmo dia</td></tr>
 *   <tr><td>06/04/2026</td><td>+4,00</td><td>casa com Aplicação −4,00 do mesmo dia</td></tr>
 *   <tr><td>02/04/2026</td><td>+318,23</td><td>casa com Aplicação −318,23 do mesmo dia</td></tr>
 *   <tr><td>02/04/2026</td><td>+238,19</td><td>casa com Aplicação −238,19 do mesmo dia</td></tr>
 *   <tr><td>08/12/2025</td><td>+1,20</td><td>casa com Aplicação −1,20 do mesmo dia</td></tr>
 *   <tr><td>01/09/2025</td><td>+4,00</td><td>casa com Compra no débito −4,00 do mesmo dia</td></tr>
 *   <tr><td>30/06/2025</td><td>+4,00</td><td>casa com Compra no débito −4,00 do mesmo dia</td></tr>
 *   <tr><td>08/04/2025</td><td>+3,50</td><td>casa com Compra no débito −3,50 do mesmo dia</td></tr>
 *   <tr><td>22/04/2026</td><td>+539,70</td><td><b>não casa com nada</b></td></tr>
 * </table>
 *
 * <p><b>O nono é a razão de a varredura ser conservadora.</b> Em 22/04/2026 o
 * saldo do dia fechou em −539,70 e o banco lançou um "Estorno" de exatamente
 * R$ 539,70 zerando a conta: é correção de saldo negativo, não devolução de
 * compra. Não há uma compra de 539,70 para parear, e inventar um par ali
 * apagaria uma receita real. <b>Sem par exato, não marca.</b>
 *
 * <p><b>Marca, não apaga</b> — mesma decisão da V26 e da V28. As duas linhas
 * existem no extrato de verdade e o saldo fecha com elas; o que muda é só a
 * soma.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundReconciliationService {

    /**
     * Janela do pareamento, em dias de calendário.
     *
     * <p>Os oito pares medidos são todos do MESMO dia, e mesmo assim a janela é
     * de três: o estorno de compra no cartão costuma cair um ou dois dias
     * depois da compra, e a amostra do dono é pequena demais para transformar
     * "todos no mesmo dia" em regra. Três é curto o bastante para não varrer um
     * mês inteiro atrás de coincidência de valor.
     */
    static final long JANELA_DIAS = 3;

    /**
     * Janela quando o estorno NOMEIA a compra (EC-236).
     *
     * <p>Quarenta e cinco dias, contra os três do pareamento por valor, e a
     * diferença é o quanto o nome vale como prova. O Nubank escreve
     * {@code Estorno de "Mercadolivre*Homenow" (Mercado Livre)}: a compra está
     * dita na própria linha, e não há coincidência a temer. Quarenta e cinco
     * porque a compra costuma estar na fatura ANTERIOR — foi o caso dos quatro
     * estornos da fatura de setembro do dono, em que só um tinha a compra no
     * mesmo arquivo.
     */
    static final long JANELA_NOMEADA_DIAS = 45;

    /**
     * O nome da compra dentro da descrição do estorno.
     *
     * <p>Aceita aspas retas e curvas: o mesmo banco escreve as duas conforme o
     * canal (app, OFX, fatura em PDF). Exige pelo menos quatro caracteres —
     * um nome de duas letras entre aspas casaria com meio extrato.
     */
    private static final Pattern NOME_DA_COMPRA =
            Pattern.compile("[\"\u201c\u201d']([^\"\u201c\u201d']{4,})[\"\u201c\u201d']");

    /**
     * O que marca um crédito como estorno na descrição.
     *
     * <p>Só o crédito precisa se anunciar — a compra do outro lado é uma compra
     * comum, e é justamente por isso que o pareamento existe. "Estorno" é o
     * termo do Inter; os outros vêm dos formatos que já lemos e do vocabulário
     * de cartão.
     */
    private static final List<String> MARCAS_DE_ESTORNO = List.of(
            "estorno", "estornado", "devolucao", "devolvido", "reembolso",
            "cancelamento", "chargeback", "refund");

    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;

    /**
     * Acha os pares e marca os dois lados.
     *
     * @param dryRun true = só relata. É como o dono olha antes de qualquer
     *               marca, porque o critério é heurístico e a conta é dele.
     */
    public Outcome sweep(String email, boolean dryRun) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
        List<BankTransaction> todas = bankTransactionRepository
                .findAllByUserIdOrderByDateDesc(user.getId());

        // Agrupa por valor ABSOLUTO: as duas pernas de um estorno têm o mesmo
        // valor e sinais opostos. stripTrailingZeros pelo mesmo motivo da
        // varredura de duplicatas — 4.0000 e 4.00 são o mesmo dinheiro e
        // chaves diferentes num BigDecimal.
        Map<BigDecimal, List<BankTransaction>> porValor = new LinkedHashMap<>();
        for (BankTransaction tx : todas) {
            // Linha que ja saiu das somas nao precisa de um segundo motivo para
            // sair, e pare-la so inflaria o relatorio de estornos com pares que
            // nao mudam numero nenhum. O estorno de aplicacao do dono e o caso:
            // as duas pernas ja sao movimento conta <-> investimento (EC-214)
            if (tx.isIgnored() || tx.isRefunded() || tx.isInternalTransfer()) continue;
            porValor.computeIfAbsent(tx.getAmount().abs().stripTrailingZeros(),
                    k -> new ArrayList<>()).add(tx);
        }

        List<Pair> pares = new ArrayList<>();
        List<UUID> marcar = new ArrayList<>();
        List<UUID[]> vinculos = new ArrayList<>();
        List<Partial> parciais = new ArrayList<>();

        // EC-236: primeiro os que NOMEIAM a compra. Vêm antes porque o nome é
        // prova mais forte que a coincidência de valor — deixá-los para o
        // pareamento por valor arriscaria casar o crédito com outra compra do
        // mesmo preço que por acaso esteja mais perto no tempo.
        Set<UUID> consumidos = new HashSet<>();
        for (BankTransaction credito : todas) {
            if (foraDaVarredura(credito) || credito.getAmount().signum() <= 0) continue;
            String nome = nomeDaCompraCitada(credito.getDescription());
            if (nome == null) continue;

            BankTransaction compra = null;
            for (BankTransaction candidata : todas) {
                if (foraDaVarredura(candidata) || candidata.getAmount().signum() >= 0) continue;
                if (consumidos.contains(candidata.getId())) continue;
                if (candidata.getDate().isAfter(credito.getDate())) continue;
                if (Math.abs(ChronoUnit.DAYS.between(credito.getDate().toLocalDate(),
                        candidata.getDate().toLocalDate())) > JANELA_NOMEADA_DIAS) continue;
                if (!CounterpartyMatcher.normalize(candidata.getDescription())
                        .contains(CounterpartyMatcher.normalize(nome))) continue;
                // A compra mais RECENTE que ainda cabe: quem compra duas vezes
                // na mesma loja recebe de volta a compra mais próxima
                if (compra == null || candidata.getDate().isAfter(compra.getDate())) {
                    compra = candidata;
                }
            }
            if (compra == null) continue;

            int comparacao = credito.getAmount().compareTo(compra.getAmount().abs());
            if (comparacao > 0) {
                // Crédito MAIOR que a compra citada: não é o estorno dela.
                // Marcar aqui somaria errado nas duas pontas
                continue;
            }
            if (comparacao < 0) {
                // PARCIAL — devolveram parte. Reportado e NÃO marcado, de
                // propósito: marcar só o crédito tiraria R$ 18,50 da receita e
                // deixaria a compra inteira na despesa, o que erra MAIS que
                // deixar os dois contando (o líquido, hoje, está certo).
                // Descontar da compra precisa de coluna nova e de uma decisão
                // do dono sobre o que a categoria deve mostrar.
                parciais.add(new Partial(compra.getId(), credito.getId(),
                        credito.getAmount(), compra.getAmount().abs(),
                        compra.getDescription(), credito.getDescription()));
                continue;
            }

            consumidos.add(compra.getId());
            consumidos.add(credito.getId());
            pares.add(new Pair(compra.getId(), credito.getId(), credito.getAmount().abs(),
                    compra.getDate().toLocalDate().toString(),
                    credito.getDate().toLocalDate().toString(),
                    compra.getDescription(), credito.getDescription()));
            marcar.add(compra.getId());
            marcar.add(credito.getId());
            vinculos.add(new UUID[] {credito.getId(), compra.getId()});
        }
        for (List<BankTransaction> mesmoValor : porValor.values()) {
            if (mesmoValor.size() < 2) continue;
            List<BankTransaction> compras = new ArrayList<>(mesmoValor.stream()
                    .filter(t -> t.getAmount().signum() < 0).toList());

            for (BankTransaction credito : mesmoValor) {
                if (credito.getAmount().signum() <= 0 || !pareceEstorno(credito)) continue;
                if (consumidos.contains(credito.getId())) continue;
                BankTransaction compra = null;
                for (BankTransaction candidata : compras) {
                    if (consumidos.contains(candidata.getId())) continue;
                    long dias = Math.abs(ChronoUnit.DAYS.between(
                            credito.getDate().toLocalDate(), candidata.getDate().toLocalDate()));
                    // A compra vem ANTES do estorno, nunca depois: dinheiro não
                    // volta de uma compra que ainda não aconteceu
                    boolean naOrdem = !candidata.getDate().isAfter(credito.getDate());
                    if (dias <= JANELA_DIAS && naOrdem) {
                        compra = candidata;
                        break;
                    }
                }
                // Sem par exato não marca — o "Estorno" de R$ 539,70 do
                // 22/04/2026 é correção de saldo, e marcá-lo apagaria uma
                // receita real da soma
                if (compra == null) continue;
                compras.remove(compra);
                pares.add(new Pair(compra.getId(), credito.getId(), credito.getAmount().abs(),
                        compra.getDate().toLocalDate().toString(),
                        credito.getDate().toLocalDate().toString(),
                        compra.getDescription(), credito.getDescription()));
                marcar.add(compra.getId());
                marcar.add(credito.getId());
                vinculos.add(new UUID[] {credito.getId(), compra.getId()});
            }
        }

        if (!dryRun && !marcar.isEmpty()) {
            bankTransactionRepository.markAsRefundPair(user.getId(), marcar);
            for (UUID[] vinculo : vinculos) {
                bankTransactionRepository.linkRefund(user.getId(), vinculo[0], vinculo[1]);
            }
        }
        BigDecimal volume = pares.stream().map(Pair::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("Varredura de estornos: {} par(es), volume {}, dryRun={}, user={}",
                pares.size(), volume, dryRun, user.getId());
        return new Outcome(todas.size(), pares.size(), volume, dryRun, pares, parciais);
    }

    /**
     * O crédito se anuncia como estorno?
     *
     * <p>Compara sem acento e em minúscula porque as fontes escrevem
     * "Devolução", "DEVOLUCAO" e "devolucao" para a mesma coisa.
     */
    static boolean pareceEstorno(BankTransaction tx) {
        String descricao = normalizar(tx.getDescription());
        if (descricao.isEmpty()) return false;
        return MARCAS_DE_ESTORNO.stream().anyMatch(descricao::contains);
    }

    static String normalizar(String texto) {
        if (texto == null) return "";
        return java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    /** Um par: a compra, o crédito que a desfez, e o que cada lado dizia. */
    public record Pair(UUID purchaseId, UUID refundId, BigDecimal amount,
                       String purchaseDate, String refundDate,
                       String purchaseDescription, String refundDescription) {
    }

    /**
     * Um estorno PARCIAL: devolveram parte da compra.
     *
     * <p>Achado na fatura real do dono: {@code Estorno de
     * "Mercadolivre*Homenow"} de R$ 18,50 sobre uma compra de R$ 604,91.
     * Reportado e não marcado — ver o comentário no pareamento por nome.
     */
    public record Partial(UUID purchaseId, UUID refundId, BigDecimal refundedAmount,
                          BigDecimal purchaseAmount, String purchaseDescription,
                          String refundDescription) {
    }

    /**
     * @param partials estornos parciais achados; reportados para o dono ver, e
     *                 deliberadamente NÃO marcados
     */
    public record Outcome(int scanned, int pairs, BigDecimal volume, boolean dryRun,
                          List<Pair> details, List<Partial> partials) {

        /** Sem parciais — como as chamadas antigas montavam o resultado. */
        public Outcome(int scanned, int pairs, BigDecimal volume, boolean dryRun,
                       List<Pair> details) {
            this(scanned, pairs, volume, dryRun, details, List.of());
        }
    }

    /** Linha que já saiu das somas por outro motivo não entra na varredura. */
    private static boolean foraDaVarredura(BankTransaction tx) {
        return tx.isIgnored() || tx.isRefunded() || tx.isInternalTransfer();
    }

    /**
     * O nome da compra que o estorno cita, ou nulo.
     *
     * <p>É o padrão do Nubank: {@code Estorno de "Mercadolivre*Homenow"
     * (Mercado Livre)}. Só vale quando a linha também se anuncia como estorno
     * — um texto qualquer com aspas não é devolução de nada.
     */
    static String nomeDaCompraCitada(String descricao) {
        if (descricao == null) return null;
        String normalizado = normalizar(descricao);
        if (MARCAS_DE_ESTORNO.stream().noneMatch(normalizado::contains)) return null;
        Matcher m = NOME_DA_COMPRA.matcher(descricao);
        return m.find() ? m.group(1).trim() : null;
    }
}
