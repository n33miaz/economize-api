package br.com.economize.postgres;

import br.com.economize.model.MfaRecoveryCode;
import br.com.economize.model.User;
import br.com.economize.model.UserMfa;
import br.com.economize.repository.MfaRecoveryCodeRepository;
import br.com.economize.repository.UserMfaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Segundo fator consumido por duas requisições ao mesmo tempo.
 *
 * <p>O código de recuperação e o passo do TOTP valem UMA vez. Quem garante
 * isso não é a leitura ("ainda não foi usado?") e sim o UPDATE condicional:
 * duas requisições com o mesmo código podem passar juntas pela leitura. Aqui a
 * disputa é no banco de verdade, e a soma dos "1" tem de ser exatamente 1.
 */
@DisplayName("Postgres: MFA — código e passo TOTP aceitos uma vez só")
class MfaConsumePostgresIT extends PostgresIntegrationBase {

    @Autowired
    private MfaRecoveryCodeRepository recoveryCodeRepository;

    @Autowired
    private UserMfaRepository userMfaRepository;

    @RepeatedTest(5)
    @DisplayName("Código de recuperação disputado por várias threads: uma aceita")
    void codigoDeRecuperacaoUmaVez() throws Exception {
        UUID codigo = codigoDeRecuperacao();

        List<Disputa<Integer>> r = emCorrida(4,
                i -> recoveryCodeRepository.consumeIfUnused(codigo, OffsetDateTime.now()));

        assertThat(r).allMatch(Disputa::ok);
        assertThat(r.stream().mapToInt(Disputa::valor).sum()).isEqualTo(1);
    }

    @RepeatedTest(5)
    @DisplayName("Mesmo passo TOTP disputado por várias threads: um aceito")
    void passoTotpUmaVez() throws Exception {
        UUID mfa = segundoFator(41L);

        List<Disputa<Integer>> r = emCorrida(4, i -> userMfaRepository.consumeStepIfNewer(mfa, 42L));

        assertThat(r).allMatch(Disputa::ok);
        assertThat(r.stream().mapToInt(Disputa::valor).sum()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select last_used_step from user_mfa where id = ?",
                Long.class, mfa)).isEqualTo(42L);
    }

    @Test
    @DisplayName("Com a trava segura: o segundo uso do código espera e é recusado")
    void codigoComTravaSegura() throws Exception {
        UUID codigo = codigoDeRecuperacao();

        List<Integer> r = comTravaSegura(
                () -> recoveryCodeRepository.consumeIfUnused(codigo, OffsetDateTime.now()),
                () -> recoveryCodeRepository.consumeIfUnused(codigo, OffsetDateTime.now()));

        assertThat(r).containsExactly(1, 0);
    }

    @Test
    @DisplayName("Com a trava segura: o mesmo passo TOTP espera e é recusado")
    void passoComTravaSegura() throws Exception {
        UUID mfa = segundoFator(null);

        List<Integer> r = comTravaSegura(
                () -> userMfaRepository.consumeStepIfNewer(mfa, 7L),
                () -> userMfaRepository.consumeStepIfNewer(mfa, 7L));

        assertThat(r).containsExactly(1, 0);
    }

    private UUID codigoDeRecuperacao() {
        User user = novoUsuario();
        return tx.execute(s -> recoveryCodeRepository.save(MfaRecoveryCode.builder()
                .user(user).codeHash("$2a$10$hash-de-teste").build()).getId());
    }

    private UUID segundoFator(Long ultimoPasso) {
        User user = novoUsuario();
        return tx.execute(s -> userMfaRepository.save(UserMfa.builder()
                .user(user).secretCipher("cifrado-de-teste").enabled(true)
                .lastUsedStep(ultimoPasso).build()).getId());
    }
}
