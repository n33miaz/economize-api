package br.com.economize.repository;

import br.com.economize.model.SweepRun;
import br.com.economize.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O UPDATE condicional do desfazer, contra o schema do H2: é ele que garante
 * que dois desfazer simultâneos não soltem as linhas duas vezes, então precisa
 * ser provado no banco, não só num mock que devolve 0 ou 1.
 */
@DataJpaTest
@DisplayName("Passadas dos vigias: marcar como desfeita")
class SweepRunRepositoryTest {

    @Autowired
    private SweepRunRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager em;

    private User user(String email) {
        return userRepository.save(User.builder()
                .name(email).email(email).password("nao-importa")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
    }

    @Test
    @DisplayName("Só o primeiro desfazer muda a linha; o segundo vê 0 e não pode liberar de novo")
    void soOPrimeiroVence() {
        User dono = user("dono@economize.test");
        SweepRun run = repository.save(SweepRun.builder()
                .user(dono).kind(SweepRun.Kind.DUPLICATE).affected(2).build());
        em.flush();

        assertThat(repository.markUndone(run.getId(), dono.getId(), OffsetDateTime.now())).isEqualTo(1);
        assertThat(repository.markUndone(run.getId(), dono.getId(), OffsetDateTime.now())).isZero();

        em.clear();
        assertThat(repository.findById(run.getId()).orElseThrow().getUndoneAt()).isNotNull();
    }

    @Test
    @DisplayName("Passada de outro dono não é marcada")
    void donoEFiltro() {
        User dono = user("dono@economize.test");
        User vizinho = user("vizinho@economize.test");
        SweepRun run = repository.save(SweepRun.builder()
                .user(dono).kind(SweepRun.Kind.REFUND).affected(1).build());
        em.flush();

        assertThat(repository.markUndone(run.getId(), vizinho.getId(), OffsetDateTime.now())).isZero();
    }
}
