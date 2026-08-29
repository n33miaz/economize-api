package br.com.economize.service.statement.category;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MEDIÇÃO da cobertura do vocabulário do sistema sobre descritivos de CARTÃO DE
 * CRÉDITO (EC-113).
 *
 * <p><b>O que este teste mede, e o que ele não mede.</b> O corpus abaixo é
 * curado: são formas reais de descritivo de fatura (prefixo de adquirente colado
 * ao nome, sufixo de cidade, nome truncado pela maquininha), mas a proporção
 * entre marcas conhecidas e comércio local é ESCOLHA DE QUEM ESCREVEU, não uma
 * amostra de produção. Ele serve para (a) provar que a compra de cartão passa
 * pelo mesmo motor do extrato bancário e (b) travar regressão de cobertura. A
 * medição de produção só ficou POSSÍVEL com a dimensão de conta desta mesma
 * rodada: antes dela nenhuma consulta sabia separar linha de cartão de linha de
 * conta corrente.
 *
 * <p>A camada medida é a de keywords, porque é ela que decide o destino de uma
 * COMPRA (débito) num usuário sem regra aprendida: sem acerto de keyword a
 * transação nasce UNCATEGORIZED e vai para a fila de revisão. O fallback por
 * tipo não socorre compra nenhuma — ele só existe para crédito.
 */
class CardDescriptionCoverageTest {

    private final RuleBasedCategorizationService keywords = new RuleBasedCategorizationService();

    /**
     * Descritivos de compra como a fatura os entrega. Divididos só para leitura:
     * a medição soma os dois blocos.
     */
    private static final List<String> MARCAS = List.of(
            "Ifood *Ifood", "Uber* Trip", "99app *99app", "Cabify",
            "Ebn*Netflix.Com", "Spotify", "Disney Plus", "Hbo Max", "Amazon Br",
            "Mercadolivre*2 Produt", "Shopee *Shopee", "Aliexpress", "Magazine Luiza",
            "Renner", "Riachuelo", "Zara Brasil", "Centauro", "Netshoes",
            "Kabum", "Pichau", "Petz", "Cobasi",
            "Assai Atacadista Sao Paulo Bra", "Carrefour", "Supermercado Dia",
            "Hortifruti Natural", "Pag*Padariabomdia", "Padaria Real",
            "Restaurante Do Ze", "Pizzaria Bella", "Burger King", "Mc Donalds",
            "Outback Steakhouse", "Starbucks",
            "Posto Ipiranga", "Auto Posto Rio", "Sem Parar", "Estacionamento Shopping",
            "Drogaria Sao Paulo", "Drogasil", "Panvel Farmacias", "Unimed",
            "Clinica Odontologica", "Academia Bluefit", "Barbearia Do Joao",
            "Udemy", "Alura Cursos", "Livraria Cultura",
            "Steam Games", "Playstation Network", "Microsoft*Xbox",
            "Cinemark", "Ingresso.Com", "Sympla",
            "Airbnb", "Booking.Com", "Latam Airlines",
            "Iof Transacao Internacional", "Anuidade Diferenciada", "Juros De Mora",
            "Encargos De Atraso", "Pagamento De Fatura", "Estorno De Compra");

    /**
     * A cauda longa da fatura: comércio local e razão social truncada. Nenhum
     * vocabulário fechado alcança isto — é para eles que existem a fila de
     * revisão e a regra aprendida.
     */
    private static final List<String> CAUDA_LONGA = List.of(
            "Vs Comercio De Alim", "R S Servicos Ltda", "Comercial Mtz",
            "Ac Confeccoes", "Lj 1234 Shopping Morumbi", "Mp *Lojadoze",
            "Dl *Google Youtubepre", "Apple.Com/Bill", "Google *Google Storage",
            "Ton *Nsedgb", "Pagseguro *Elc", "Sumup *Mariaflor");

    @Test
    @DisplayName("MEDIÇÃO: cobertura do motor sobre descritivos de fatura de cartão")
    void measureCardDescriptionCoverage() {
        List<String> corpus = new ArrayList<>(MARCAS);
        corpus.addAll(CAUDA_LONGA);

        List<String> semCategoria = new ArrayList<>();
        List<String> resolucoes = new ArrayList<>();
        for (String description : corpus) {
            keywords.match(description).ifPresentOrElse(
                    hit -> resolucoes.add(description + " -> " + hit.systemKey()),
                    () -> semCategoria.add(description));
        }

        int total = corpus.size();
        int resolvidas = total - semCategoria.size();
        System.out.printf("%n[EC-113] Cobertura de descritivos de CARTÃO: %d/%d resolvidas (%.1f%%), "
                        + "%d sem categoria%n  sem categoria: %s%n  resolucoes: %s%n",
                resolvidas, total, 100.0 * resolvidas / total, semCategoria.size(), semCategoria, resolucoes);

        // Piso de regressão, não meta: quem apertar o vocabulário e derrubar a
        // cobertura de cartão descobre aqui, não em produção
        assertThat(resolvidas)
                .as("cobertura do vocabulário sobre descritivos de fatura de cartão")
                .isGreaterThanOrEqualTo(60);
    }

    @Test
    @DisplayName("prefixo de adquirente colado ao nome não impede o acerto da keyword")
    void acquirerPrefixShouldNotBlockKeywordMatch() {
        // as keywords rodam no descritivo CRU e casam por pedaço: é por isso que
        // "Pag*Ifood" e "Ebn*Netflix.Com" acertam mesmo com o prefixo grudado
        assertThat(keywords.match("Pag*Ifood Sao Paulo Bra")).isPresent()
                .get().extracting(RuleBasedCategorizationService.Hit::systemKey)
                .isEqualTo("FOOD_DELIVERY");
        assertThat(keywords.match("Ebn*Netflix.Com")).isPresent()
                .get().extracting(RuleBasedCategorizationService.Hit::systemKey)
                .isEqualTo("LEISURE_STREAMING");
    }
}
