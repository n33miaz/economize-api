package br.com.economize.dto.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EC-210 e EC-211 — ver antes de conectar, e coleta progressiva.
 *
 * <p><b>O que este teste protege.</b> Hoje dá para criar conta com nome,
 * e-mail e senha, importar um extrato e ver o app funcionando — <b>sem CPF,
 * sem banco, sem telefone</b>. Isso não foi construído agora: é como o
 * cadastro já é. O que não existia era o que impede de deixar de ser.
 *
 * <p><b>Por que ele é necessário.</b> Campo de cadastro é a coisa mais fácil
 * do mundo de acrescentar: alguém precisa de CPF para uma integração, adiciona
 * um campo "só para quem for usar", e seis meses depois ele é obrigatório para
 * todo mundo porque ninguém lembra por que era opcional. O custo aparece do
 * outro lado — na pessoa que abandona o cadastro antes de ver uma tela útil.
 *
 * <p><b>A regra:</b> nenhum dado é pedido antes de a função que precisa dele
 * ser usada. CPF só quando houver Open Finance de verdade; banco só quando a
 * pessoa quiser conectar um; nada disso para quem só quer arrastar um CSV.
 */
class OnboardingContractTest {

    /**
     * O que o cadastro pode pedir. Crescer esta lista é uma decisão de
     * produto — e é para ela ser tomada de propósito que o teste existe.
     */
    private static final List<String> PERMITIDOS = List.of("name", "email", "password");

    /** Os dados que NÃO podem ser pedidos na porta de entrada. */
    private static final List<String> PROIBIDOS = List.of(
            "cpf", "cnpj", "documento", "document", "telefone", "phone", "celular",
            "banco", "bank", "nascimento", "birth", "endereco", "address", "renda",
            "income", "cep", "zip");

    @Test
    @DisplayName("O cadastro pede exatamente nome, e-mail e senha — nada mais")
    void cadastroPedeApenasOEssencial() {
        List<String> campos = Arrays.stream(RegisterRequest.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic() && !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .toList();

        assertThat(campos)
                .as("um campo novo aqui é um motivo a mais para abandonar o cadastro (EC-211)")
                .containsExactlyInAnyOrderElementsOf(PERMITIDOS);
    }

    @Test
    @DisplayName("Nenhum dado de identidade, contato ou banco entra na porta de entrada")
    void nenhumDadoProibidoNoCadastro() {
        List<String> ofensores = Arrays.stream(RegisterRequest.class.getDeclaredFields())
                .map(f -> f.getName().toLowerCase(Locale.ROOT))
                .filter(nome -> PROIBIDOS.stream().anyMatch(nome::contains))
                .toList();

        assertThat(ofensores)
                .as("cada um destes é pedido QUANDO a função que precisa dele for usada")
                .isEmpty();
    }

    @Test
    @DisplayName("Importar extrato não exige conta: dá para ver antes de conectar")
    void uploadNaoExigeConta() throws NoSuchMethodException {
        Method upload = Arrays.stream(
                        br.com.economize.controller.BankStatementController.class.getMethods())
                .filter(m -> m.getName().equals("upload"))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException("upload"));

        // O `accountId` é OPCIONAL de propósito: exigir conta para importar um
        // arquivo obrigaria a conectar um banco antes de ver o app funcionando,
        // que é exatamente a porta que o EC-210 existe para abrir
        var parametro = Arrays.stream(upload.getParameters())
                .filter(p -> p.isAnnotationPresent(
                        org.springframework.web.bind.annotation.RequestParam.class))
                .findFirst()
                .orElseThrow();

        assertThat(parametro.getAnnotation(
                org.springframework.web.bind.annotation.RequestParam.class).required())
                .as("importar um extrato não pode depender de ter conectado um banco")
                .isFalse();
    }

    @Test
    @DisplayName("A guarda enxerga um campo proibido — senão ela não vale nada")
    void aGuardaPegaOCampoProibido() {
        List<String> falsos = List.of("cpf", "userCpf", "telefoneCelular", "dataNascimento",
                "bancoPrincipal", "cepResidencial");

        for (String campo : falsos) {
            assertThat(PROIBIDOS.stream()
                    .anyMatch(p -> campo.toLowerCase(Locale.ROOT).contains(p)))
                    .as("deveria pegar: %s", campo)
                    .isTrue();
        }
    }
}
