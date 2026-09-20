package br.com.economize.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A saúde da conexão bancária, que era invisível até 17/09/2026.
 *
 * <p><b>O defeito que trouxe estas regras.</b> A conta do dono mostrava o saldo
 * certo — R$ 250,13, igual ao painel do provedor — e, ao lado, a fatura de
 * setembro de dois cartões com total R$ 0,00 e o pagamento cheio. Nove compras
 * entre 05/09 e 14/09, somando R$ 373,66, simplesmente não existiam. O
 * sincronizador respondia HTTP 200 com {@code itemsSynced: 5} e
 * {@code transactionsImported: 0}, e nada na tela dizia que aquele retrato do
 * banco era de 14/08.
 *
 * <p>O registro guardava apenas {@code lastSyncedAt} — quando NÓS lemos o
 * provedor —, que estava sempre fresco e por isso mentia por omissão. A idade
 * que importa é outra: quando o PROVEDOR leu o banco.
 */
class PluggyItemHealthTest {

    private PluggyItem item() {
        return PluggyItem.builder().itemId("abc").connectorName("Banco").build();
    }

    @Test
    @DisplayName("sem nunca ter perguntado ao provedor, a idade do dado é desconhecida — e não zero")
    void idadeDesconhecida() {
        assertThat(item().horasDesdeAColeta()).isEmpty();
    }

    /**
     * Zero seria a pior resposta possível: é indistinguível de "coletado agora"
     * e foi exatamente o silêncio otimista que escondeu o defeito.
     */
    @Test
    @DisplayName("nunca perguntado NÃO é tratado como recém-coletado")
    void desconhecidoNaoEhFresco() {
        PluggyItem i = item();

        assertThat(i.horasDesdeAColeta().orElse(-1)).isEqualTo(-1);
    }

    @Test
    @DisplayName("a idade conta do momento em que o provedor leu o banco")
    void idadeVemDoProvedor() {
        PluggyItem i = item();
        i.setProviderUpdatedAt(OffsetDateTime.now().minusHours(30));

        assertThat(i.horasDesdeAColeta()).hasValue(30);
    }

    @Test
    @DisplayName("coleta de poucas horas, com status UPDATED, não pede atenção")
    void recenteEstaSaudavel() {
        PluggyItem i = item();
        i.setStatus("UPDATED");
        i.setProviderUpdatedAt(OffsetDateTime.now().minusHours(3));

        assertThat(i.precisaDeAtencao()).isFalse();
    }

    /**
     * O caso do dono: o provedor não reportava erro nenhum — só não coletava
     * havia um mês. Sem esta regra, a conexão continuaria "verde" enquanto o
     * extrato envelhecia.
     */
    @Test
    @DisplayName("status UPDATED com coleta de um mês atrás AINDA pede atenção")
    void velhoPedeAtencaoMesmoSemErro() {
        PluggyItem i = item();
        i.setStatus("UPDATED");
        i.setProviderUpdatedAt(OffsetDateTime.now().minusDays(33));

        assertThat(i.precisaDeAtencao()).isTrue();
    }

    @Test
    @DisplayName("erro de login pede atenção mesmo com coleta recente")
    void erroPedeAtencao() {
        PluggyItem i = item();
        i.setStatus("LOGIN_ERROR");
        i.setProviderUpdatedAt(OffsetDateTime.now().minusMinutes(5));

        assertThat(i.precisaDeAtencao()).isTrue();
    }

    @Test
    @DisplayName("conexão nunca consultada não é dada como saudável")
    void nuncaConsultadaNaoEhSaudavel() {
        PluggyItem i = item();
        i.setStatus("WAITING_USER_INPUT");

        assertThat(i.precisaDeAtencao()).isTrue();
    }

    /**
     * A borda exata: 24 horas ainda passa, 25 não. Fica escrito porque o número
     * é decisão de produto, não acidente — banco publica lançamento com atraso
     * de horas, e acusar a cada 6 encheria a tela de alarme falso.
     */
    @Test
    @DisplayName("a fronteira é 24 horas de idade do dado")
    void fronteiraDeUmDia() {
        PluggyItem limite = item();
        limite.setStatus("UPDATED");
        limite.setProviderUpdatedAt(OffsetDateTime.now().minusHours(24));

        PluggyItem passou = item();
        passou.setStatus("UPDATED");
        passou.setProviderUpdatedAt(OffsetDateTime.now().minusHours(25));

        assertThat(limite.precisaDeAtencao()).isFalse();
        assertThat(passou.precisaDeAtencao()).isTrue();
    }
}
