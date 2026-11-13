package br.com.economize.postgres;

import br.com.economize.model.User;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.RefundReconciliationService;
import br.com.economize.service.StatementImportWriter;
import br.com.economize.service.WatchmanService;
import br.com.economize.service.privacy.AccountDataEraser;
import br.com.economize.service.privacy.SchemaTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base da suíte de integração em POSTGRES DE VERDADE.
 *
 * <p><b>Por que existe.</b> O resto da suíte roda em H2 com o Flyway desligado
 * e dentro da transação do próprio teste. Isso prova a regra, mas não prova o
 * que só o banco decide: o rollback de uma transação que falha no commit, e
 * quem vence quando duas transações disputam a mesma linha. Os UPDATEs
 * condicionais ({@code ... where used_at is null}) só são trava porque o
 * Postgres, em READ COMMITTED, bloqueia a segunda escrita na linha e reavalia
 * o WHERE depois que a primeira confirma — e isso o H2 de teste não ensaia.
 *
 * <p><b>Como está montada.</b>
 * <ul>
 *   <li>Um container só para a suíte inteira (singleton estático, iniciado uma
 *       vez por JVM). {@code @Testcontainers/@Container} pararia e subiria um
 *       por classe, e a máquina tem memória curta. O Ryuk derruba o container
 *       quando a JVM sai.</li>
 *   <li>Mesma imagem do job "Migrations contra Postgres" do CI, com as
 *       migrations do Flyway aplicadas de verdade e o Hibernate em
 *       {@code validate}: o schema é o de produção, não o que a entidade
 *       acha que é.</li>
 *   <li>{@code NOT_SUPPORTED}: o teste NÃO abre transação. Cada thread abre a
 *       sua pelo {@link TransactionTemplate} e confirma de verdade — sem isso
 *       as outras threads nem enxergariam os dados de preparo.</li>
 *   <li>Como tudo é confirmado, nada é apagado entre testes: cada cenário cria
 *       o próprio usuário com e-mail aleatório e só olha para as linhas dele.</li>
 *   <li>A configuração é idêntica em todas as subclasses, para o Spring
 *       reaproveitar um contexto só.</li>
 * </ul>
 *
 * <p>Fica fora do {@code mvn test} de sempre pela tag {@code postgres} (ver o
 * surefire no pom.xml); roda com {@code -Ppostgres-it}.
 */
@Tag("postgres")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({StatementImportWriter.class, RefundReconciliationService.class, WatchmanService.class,
        AccountDataEraser.class, SchemaTables.class})
public abstract class PostgresIntegrationBase {

    /** Teto de espera de qualquer passo concorrente: trava errada falha, não pendura a JVM. */
    protected static final long ESPERA_SEGUNDOS = 30;

    @SuppressWarnings("resource") // fechado pelo Ryuk quando a JVM termina
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("economize_it")
                    .withUsername("economize")
                    .withPassword("economize");

    static {
        POSTGRES.start();
    }

    /**
     * Desfaz, uma a uma, as linhas de H2 do application.properties de teste.
     * Esquecer qualquer uma delas faz o contexto subir contra o banco errado ou
     * com o dialeto errado — e o teste passar por motivo nenhum.
     */
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private javax.sql.DataSource dataSource;

    protected TransactionTemplate tx;

    /** Leitura direta, sempre fresca: nada de cache do contexto de persistência. */
    protected JdbcTemplate jdbc;

    @BeforeEach
    void preparar() {
        this.tx = new TransactionTemplate(transactionManager);
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Usuário novo, já confirmado no banco, com e-mail que nenhum outro teste usa. */
    protected User novoUsuario() {
        String email = "it-" + UUID.randomUUID() + "@economize.test";
        return tx.execute(s -> userRepository.save(User.builder()
                .name("Integração").email(email).password("nao-importa")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build()));
    }

    /** O que uma das threads da corrida obteve: um valor ou a exceção que levou. */
    protected record Disputa<T>(T valor, Throwable erro) {
        boolean ok() {
            return erro == null;
        }
    }

    /**
     * Dispara {@code n} tarefas AO MESMO TEMPO, cada uma na própria transação.
     *
     * <p>Todas as threads ficam prontas e paradas no mesmo portão; só então o
     * portão abre. Sem isso a primeira thread costuma terminar antes de a
     * última nascer, e a "corrida" vira execução em fila. Ainda assim não há
     * garantia de sobreposição — por isso os cenários repetem a disputa, e as
     * travas principais também são provadas pela forma determinística em
     * {@link #comTravaSegura}.
     */
    protected <T> List<Disputa<T>> emCorrida(int n, IntFunction<T> tarefa) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch prontas = new CountDownLatch(n);
            CountDownLatch portao = new CountDownLatch(1);
            List<Future<Disputa<T>>> futuros = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int indice = i;
                futuros.add(pool.submit(() -> {
                    prontas.countDown();
                    portao.await(ESPERA_SEGUNDOS, TimeUnit.SECONDS);
                    try {
                        return new Disputa<>(tx.execute(s -> tarefa.apply(indice)), null);
                    } catch (RuntimeException e) {
                        return new Disputa<T>(null, e);
                    }
                }));
            }
            assertThat(prontas.await(ESPERA_SEGUNDOS, TimeUnit.SECONDS)).isTrue();
            portao.countDown();
            List<Disputa<T>> resultados = new ArrayList<>();
            for (Future<Disputa<T>> f : futuros) {
                resultados.add(f.get(ESPERA_SEGUNDOS, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A disputa sem depender da sorte do agendador.
     *
     * <p>A primeira transação faz a escrita e NÃO confirma: fica segurando a
     * trava da linha. A segunda faz a mesma escrita e tem de ficar parada
     * esperando — o teste confere isso no próprio Postgres
     * ({@code pg_stat_activity}, espera do tipo Lock). Só então a primeira
     * confirma, e a segunda reavalia o WHERE contra a linha já alterada. É
     * exatamente o intervalo em que um "ler, decidir, salvar" deixaria as duas
     * passarem.
     *
     * @return o retorno da primeira e da segunda escrita, nessa ordem
     */
    protected <T> List<T> comTravaSegura(Supplier<T> primeira, Supplier<T> segunda) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch escreveu = new CountDownLatch(1);
            CountDownLatch liberar = new CountDownLatch(1);
            Future<T> a = pool.submit(() -> tx.execute(s -> {
                T r = primeira.get();
                escreveu.countDown();
                try {
                    liberar.await(ESPERA_SEGUNDOS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return r;
            }));
            assertThat(escreveu.await(ESPERA_SEGUNDOS, TimeUnit.SECONDS)).isTrue();

            Future<T> b = pool.submit(() -> tx.execute(s -> segunda.get()));
            aguardarAlguemEsperandoTrava();
            // Parada de verdade, e não apenas lenta: enquanto a primeira não
            // confirma, a segunda não tem como ter terminado
            assertThat(b.isDone()).as("a segunda escrita deveria estar bloqueada").isFalse();

            liberar.countDown();
            return List.of(a.get(ESPERA_SEGUNDOS, TimeUnit.SECONDS),
                    b.get(ESPERA_SEGUNDOS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private void aguardarAlguemEsperandoTrava() throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(ESPERA_SEGUNDOS);
        while (System.nanoTime() < limite) {
            Integer esperando = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity "
                            + "where datname = current_database() and wait_event_type = 'Lock'",
                    Integer.class);
            if (esperando != null && esperando > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("nenhuma transação ficou esperando a trava da linha");
    }
}
