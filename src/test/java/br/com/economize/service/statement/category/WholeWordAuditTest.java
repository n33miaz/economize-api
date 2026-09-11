package br.com.economize.service.statement.category;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EC-197 — a auditoria de casamento por palavra inteira.
 *
 * <p><b>O defeito na casa do concorrente.</b> Nas avaliações do Pierre está
 * escrito: <i>"o app interpretou uma compra em 'mercadobelo' como adobe (pq tem
 * adobe dentro da palavra)"</i>. É o casamento por pedaço aplicado a uma marca.
 *
 * <p><b>O mesmo defeito na nossa.</b> Em 10/09/2026 varri o vocabulário inteiro
 * contra os extratos reais do dono (1.779 descrições) procurando termo que
 * começasse <b>no meio</b> de uma palavra. Seis casos, quatro deles errados:
 *
 * <table><caption>O que a varredura achou</caption>
 *   <tr><th>Termo</th><th>Dentro de</th><th>Veredito</th></tr>
 *   <tr><td>lca</td><td>Manoe<b>lca</b>rdoso</td><td>errado — Pix virava CDB</td></tr>
 *   <tr><td>lci</td><td>A<b>lci</b>des</td><td>errado</td></tr>
 *   <tr><td>zara</td><td>Al<b>zara</b> Perfumaria</td><td>errado — perfumaria virava roupa</td></tr>
 *   <tr><td>posto</td><td>Im<b>posto</b> IR/IOF</td><td>errado, 20 linhas</td></tr>
 *   <tr><td>lanch</td><td>Hla<b>lanch</b>onete</td><td><b>certo</b> — é lanchonete mesmo</td></tr>
 *   <tr><td>presente</td><td>gg<b>presente</b>s</td><td><b>certo</b> — é loja de presentes</td></tr>
 * </table>
 *
 * <p>Os dois últimos são a razão de a correção <b>não</b> ter sido tornar tudo
 * palavra inteira: "restaurant" precisa cobrir restaurante(s) e "lanch"
 * precisa cobrir lanchonete. Português flexiona; sigla não.
 */
class WholeWordAuditTest {

    private final RuleBasedCategorizationService service = new RuleBasedCategorizationService();

    /**
     * A regra que substitui o conserto caso a caso.
     *
     * <p>Antes desta auditoria, cada termo curto virava palavra inteira só
     * depois de alguém tropeçar nele: "amil" dentro de CAMILA, "iof" dentro de
     * biofarma, "ted" dentro de limited, "mora" dentro de AMORA, "clear" dentro
     * de NUCLEAR. Cinco incidentes, cinco correções, nenhuma regra. Quatro
     * letras dentro de um nome de trinta é cara ou coroa.
     */
    @Test
    @DisplayName("Termo de até quatro caracteres, sem espaço, casa por palavra inteira")
    void termoCurtoESempreDePalavraInteira() {
        List<String> frouxos = RuleBasedCategorizationService.vocabulary().stream()
                .filter(term -> !term.wholeWord())
                .map(RuleBasedCategorizationService.VocabularyTerm::keyword)
                .filter(keyword -> !keyword.contains(" "))
                .filter(keyword -> keyword.length() <= 4)
                .toList();

        assertThat(frouxos)
                .as("estes casariam dentro de qualquer nome; declare-os wholeWord")
                .isEmpty();
    }

    /**
     * A guarda estrutural sozinha não basta: um termo de cinco letras também
     * mora dentro de palavra ("posto" em "imposto"). Este teste é a varredura
     * automatizada — se alguém acrescentar um termo perigoso, ele aparece aqui.
     */
    @Test
    @DisplayName("Nenhum termo por pedaço começa no meio de uma palavra do corpus")
    void nenhumTermoComecaNoMeioDeUmaPalavra() {
        List<String> flagrantes = new java.util.ArrayList<>();
        for (RuleBasedCategorizationService.VocabularyTerm term
                : RuleBasedCategorizationService.vocabulary()) {
            if (term.wholeWord()) continue;
            String keyword = term.keyword();
            if (keyword.contains(" ")) continue;
            if (PEDACOS_LEGITIMOS.contains(keyword)) continue;
            Pattern noMeio = Pattern.compile(
                    "(?<=[\\p{L}\\p{N}])" + Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
            for (String armadilha : CORPUS_DE_ARMADILHAS) {
                Matcher m = noMeio.matcher(armadilha);
                if (m.find()) flagrantes.add(keyword + " dentro de " + armadilha);
            }
        }

        assertThat(flagrantes).isEmpty();
    }

    /**
     * Prefixos que DEVEM casar por pedaço, porque o português flexiona: são a
     * razão de a regra ser sobre tamanho e não sobre "tudo palavra inteira".
     */
    private static final List<String> PEDACOS_LEGITIMOS = List.of(
            "restaurant", "lanch", "veterinar", "farmacia", "farmácia", "presente");

    /**
     * Nomes e razões sociais plausíveis num extrato brasileiro. Os cinco
     * primeiros são <b>linhas reais</b> do extrato do dono; os demais são a
     * mesma armadilha aplicada a cada termo curto do vocabulário.
     */
    private static final List<String> CORPUS_DE_ARMADILHAS = List.of(
            "Compra no débito Manoelcardoso Barueri Bra",
            "Pix enviado Alzara Perfumaria E Cosmeticos Unipessoal Ltda",
            "Imposto IR/IOF -  Tesouro direto",
            "Compra no débito Joselice Do Nascimento Barueri Bra",
            "Pix recebido Raphael Prezoutto Gasparotto",
            "Pix enviado Falcao Comercio de Alimentos",
            "Pix enviado Alcides Ferreira da Silva",
            "Compra no débito Acougue Alcatra Nobre",
            "Pix recebido Huberto Nogueira Lima",
            "Compra no débito Casa do Composto Quimico",
            "Pix enviado Camila Souza Andrade",
            "PAGSEGURO *MERCANTE COMERCIO",
            "Compra no débito Nuclear Engenharia Ltda",
            "Compra no débito Biofarma Manipulacao",
            "Pix enviado Amora Doces e Salgados",
            "TED recebido de Limited Brasil Servicos",
            "Compra no débito Uberlandia Prefeitura Taxas",
            "Pix enviado Nikefer Metais Ltda",
            "Pix enviado Zaravalho Silva Comercio",
            "Compra no débito Temulento Bar e Petiscos",
            "Pix enviado Petzinho Racoes Ltda",
            "Compra no débito Xboxeador Games Import",
            "Pix enviado Jcpires Advogados Associados",
            "Pix enviado Cdbrasil Servicos Digitais",
            "Pix enviado Rdbueno Comercio de Pecas",
            "Pix enviado Darfiel Santos Consultoria",
            "Pix enviado Irrfael Lima Transportes",
            "Compra no débito Iptuano Assessoria Ltda",
            "Pix enviado Pgblanco Contabilidade",
            "Pix recebido Vgblima Servicos Gerais",
            "Compra no débito Enelvira Machado Bolos",
            "Compra no débito Cpflorestal Madeiras",
            "Compra no débito Cptmania Turismo Ltda",
            "Compra no débito Ipvaldo Souza Autos",
            "Compra no débito Pneumatica Servicos Industriais",
            "Pix enviado Insstituto de Idiomas");

    /**
     * A outra metade da prova: o que a auditoria <b>não</b> podia quebrar.
     * Todas são linhas ou marcas reais, e todas continuam caindo onde caíam.
     */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            // as duas que provam que 'lanch' segue casando por pedaço
            "'Compra no débito No Estabelecimento Hlalanchonete Barueri Br', FOOD_RESTAURANT",
            "'Compra no débito Fernandeslanchese Barueri Bra', FOOD_RESTAURANT",
            "'Compra no débito Mp *ggpresentes Barueri Bra', SHOPPING_GIFTS",
            "'RESTAURANTES E LANCHES LTDA', FOOD_RESTAURANT",
            // as siglas que viraram palavra inteira continuam achando o que deviam
            "'Aplicação Cdb Porquinho Banco Inter Sa', INVESTMENT_FIXED",
            "'Compra LCA Banco XP', INVESTMENT_FIXED",
            "'Pagamento IPTU 2026 Prefeitura', HOUSING_PROPERTY_TAX",
            "'INSS Contribuicao Mensal', INCOME_BENEFITS",
            "'ENEL SP DISTRIBUICAO', UTILITIES_ELECTRICITY",
            "'CPFL PAULISTA ENERGIA', UTILITIES_ELECTRICITY",
            "'UBER *TRIP HELP.UBER.COM', TRANSPORT_RIDE",
            "'ZARA BRASIL LTDA', SHOPPING_CLOTHING",
            "'NIKE.COM.BR', SHOPPING_CLOTHING",
            "'PETZ COMERCIO DE ANIMAIS', SHOPPING_PET",
            "'MICROSOFT*XBOX GAME PASS', LEISURE_GAMES",
            "'REDE DE POSTOS SHELL BR', TRANSPORT_FUEL",
            "'POSTO IPIRANGA AV BRASIL', TRANSPORT_FUEL",
            "'PNEUS E RODAS COMERCIO', TRANSPORT_VEHICLE",
            "'IPVA 2026 DETRAN SP', TRANSPORT_VEHICLE",
            "'DARF PAGAMENTO RECEITA FEDERAL', FEES_TAX",
            "'TEMU.COM COMPRA ONLINE', SHOPPING_ONLINE",
            "'PGBL PREVIDENCIA BRADESCO', INVESTMENT_PENSION",
            "'CPTM BILHETE UNICO RECARGA', TRANSPORT_PUBLIC",
            "'UBEREATS PEDIDO', FOOD_DELIVERY",
    })
    @DisplayName("O que casava certo continua casando")
    void oQueCasavaCertoContinua(String descricao, String esperado) {
        Optional<RuleBasedCategorizationService.Hit> hit = service.match(descricao);

        assertThat(hit).isPresent();
        assertThat(hit.get().systemKey()).isEqualTo(esperado);
    }

    /**
     * E as armadilhas, uma a uma, com o veredito que importa: <b>não</b> cair
     * na categoria errada. Algumas podem cair em outra coisa legítima (um Pix
     * é um Pix), e é por isso que a asserção é sobre o alvo errado, não sobre
     * "nenhum resultado".
     */
    @ParameterizedTest(name = "{0} não é {1}")
    @CsvSource({
            "'Compra no débito Manoelcardoso Barueri Bra', INVESTMENT_FIXED",
            "'Pix enviado Alzara Perfumaria E Cosmeticos', SHOPPING_CLOTHING",
            "'Pix enviado Alcides Ferreira da Silva', INVESTMENT_FIXED",
            "'Compra no débito Acougue Alcatra Nobre', INVESTMENT_FIXED",
            "'Pix recebido Huberto Nogueira Lima', TRANSPORT_RIDE",
            "'Compra no débito Casa do Composto Quimico', TRANSPORT_FUEL",
            "'Pix enviado Camila Souza Andrade', HEALTH_INSURANCE",
            "'PAGSEGURO *MERCANTE COMERCIO', INSURANCE_LIFE",
            "'Compra no débito Nuclear Engenharia Ltda', INVESTMENT_VARIABLE",
            "'Compra no débito Biofarma Manipulacao', FEES_IOF",
            "'Pix enviado Amora Doces e Salgados', FEES_INTEREST",
            "'Compra no débito Uberlandia Prefeitura Taxas', TRANSPORT_RIDE",
            "'Pix enviado Zaravalho Silva Comercio', SHOPPING_CLOTHING",
            "'Compra no débito Xboxeador Games Import', LEISURE_GAMES",
            "'Pix enviado Jcpires Advogados Associados', INCOME_YIELDS",
            "'Compra no débito Iptuano Assessoria Ltda', HOUSING_PROPERTY_TAX",
            "'Compra no débito Enelvira Machado Bolos', UTILITIES_ELECTRICITY",
            "'Compra no débito Pneumatica Servicos Industriais', TRANSPORT_VEHICLE",
            "'Pix enviado Insstituto de Idiomas', INCOME_BENEFITS",
            "'Imposto IR/IOF -  Tesouro direto', TRANSPORT_FUEL",
    })
    @DisplayName("As pegadinhas não caem na categoria errada")
    void asPegadinhasNaoCaemNoLugarErrado(String descricao, String naoPodeSer) {
        Optional<RuleBasedCategorizationService.Hit> hit = service.match(descricao);

        assertThat(hit.map(RuleBasedCategorizationService.Hit::systemKey))
                .isNotEqualTo(Optional.of(naoPodeSer));
    }

    @Test
    @DisplayName("A varredura enxerga vocabulário de verdade")
    void aVarreduraEnxergaVocabulario() {
        // Sem isto, um acessor quebrado faria os testes acima passarem sempre
        assertThat(RuleBasedCategorizationService.vocabulary()).hasSizeGreaterThan(300);
    }
}
