package br.com.economize.repository;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.FamilyGroup;
import br.com.economize.model.FamilyInvite;
import br.com.economize.model.FamilyMember;
import br.com.economize.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A visão da casa contra banco (EC-149).
 *
 * <p>A promessa da §7 é "privacidade por construção": o que o membro escolheu
 * não mostrar é filtrado NA CONSULTA, não em memória depois. Só um teste que
 * executa a consulta de verdade prova isso — no serviço, com o repositório
 * dublado, a cláusula nunca roda. Aqui cada regra da cláusula tem o seu caso:
 * categoria oculta, conta não compartilhada, "extrato importado" desmarcado,
 * linha sem categoria (que NÃO é oculta) e o vizinho de fora do grupo.
 *
 * <p>As coleções vazias são o caso mais perigoso — {@code in ()} não é SQL — e
 * por isso a maioria dos casos passa por uma delas.
 */
@DataJpaTest
@DisplayName("Visão da casa e tabelas da V19 contra banco (EC-149)")
class FamilyRepositoryTest {

    private static final OffsetDateTime JULHO_10 =
            OffsetDateTime.of(2026, 7, 10, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime INICIO = JULHO_10.minusDays(9);
    private static final OffsetDateTime FIM = JULHO_10.plusDays(1);

    @Autowired
    private BankTransactionRepository transactions;

    @Autowired
    private FamilyGroupRepository groups;

    @Autowired
    private FamilyMemberRepository members;

    @Autowired
    private FamilyInviteRepository invites;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager em;

    private User dono;
    private User estranho;

    private final UUID alimentacao = UUID.randomUUID();
    private final UUID saude = UUID.randomUUID();
    private final UUID nubank = UUID.randomUUID();
    private final UUID itau = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dono = userRepository.save(user("dono@economize.test"));
        estranho = userRepository.save(user("estranho@economize.test"));
    }

    private User user(String email) {
        return User.builder()
                .name(email)
                .email(email)
                .password("nao-importa")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private BankTransaction lancamento(User owner, String valor, UUID categoria, UUID conta) {
        return transactions.save(BankTransaction.builder()
                .user(owner)
                .transactionId("ext-" + UUID.randomUUID())
                .type(new BigDecimal(valor).signum() < 0 ? "DEBIT" : "CREDIT")
                .amount(new BigDecimal(valor))
                .description("Lancamento")
                .categoryId(categoria)
                .accountId(conta)
                .date(JULHO_10)
                .reviewStatus(BankTransaction.ReviewStatus.CONFIRMED)
                .internalTransfer(false)
                .build());
    }

    private static BigDecimal somaDe(List<BankTransactionRepository.CategoryTotal> totais) {
        return totais.stream()
                .map(BankTransactionRepository.CategoryTotal::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------ a cláusula do membro

    @Nested
    @DisplayName("A consulta compartilhada")
    class ConsultaCompartilhada {

        @Test
        @DisplayName("Categoria oculta não volta em linha nem em soma")
        void categoriaOcultaNaoVoltaEmLinhaNemEmSoma() {
            lancamento(dono, "-100.00", alimentacao, null);
            lancamento(dono, "-300.00", saude, null);
            lancamento(dono, "-20.00", null, null);

            var totais = transactions.sumByCategoryShared(dono.getId(), INICIO, FIM,
                    Set.of(saude), Set.of(), true);
            var linhas = transactions.findSharedInWindow(dono.getId(), INICIO, FIM,
                    Set.of(saude), Set.of(), true);

            // A soma sem a categoria: se o total viesse do extrato inteiro, o
            // outro membro deduziria os R$ 300 escondidos pela diferença
            assertThat(totais).extracting(BankTransactionRepository.CategoryTotal::getCategoryId)
                    .containsExactlyInAnyOrder(alimentacao, null);
            assertThat(somaDe(totais)).isEqualByComparingTo("-120.00");
            assertThat(linhas).extracting(BankTransaction::getCategoryId)
                    .containsExactlyInAnyOrder(alimentacao, null);
        }

        @Test
        @DisplayName("Linha SEM categoria não é oculta — só as listadas são")
        void linhaSemCategoriaNaoEOculta() {
            lancamento(dono, "-20.00", null, null);

            var totais = transactions.sumByCategoryShared(dono.getId(), INICIO, FIM,
                    Set.of(saude), Set.of(), true);

            // `not in` com NULL dá NULL e a linha sumiria em silêncio; a cláusula
            // trata o nulo explicitamente para "Sem categoria" continuar visível
            assertThat(totais).hasSize(1);
            assertThat(totais.get(0).getCategoryId()).isNull();
        }

        @Test
        @DisplayName("Conta não compartilhada não volta; a sem conta entra com 'extrato importado' marcado")
        void contaNaoCompartilhadaNaoVolta() {
            lancamento(dono, "-100.00", alimentacao, nubank);
            lancamento(dono, "-40.00", alimentacao, itau);
            lancamento(dono, "-10.00", alimentacao, null);

            var linhas = transactions.findSharedInWindow(dono.getId(), INICIO, FIM,
                    Set.of(), Set.of(nubank), true);
            var totais = transactions.sumByCategoryShared(dono.getId(), INICIO, FIM,
                    Set.of(), Set.of(nubank), true);

            assertThat(linhas).extracting(BankTransaction::getAccountId)
                    .containsExactlyInAnyOrder(nubank, null);
            assertThat(somaDe(totais)).isEqualByComparingTo("-110.00");
        }

        @Test
        @DisplayName("includeUnassigned=false exclui a linha com account_id nulo")
        void includeUnassignedFalseExcluiSemConta() {
            lancamento(dono, "-100.00", alimentacao, nubank);
            lancamento(dono, "-10.00", alimentacao, null);

            var linhas = transactions.findSharedInWindow(dono.getId(), INICIO, FIM,
                    Set.of(), Set.of(nubank), false);
            var totais = transactions.sumByCategoryShared(dono.getId(), INICIO, FIM,
                    Set.of(), Set.of(nubank), false);

            assertThat(linhas).hasSize(1);
            assertThat(linhas.get(0).getAccountId()).isEqualTo(nubank);
            assertThat(somaDe(totais)).isEqualByComparingTo("-100.00");
        }

        @Test
        @DisplayName("Lista de contas vazia é 'todas' — e o 'extrato importado' ainda manda na linha sem conta")
        void listaVaziaETodasAsContas() {
            lancamento(dono, "-100.00", alimentacao, nubank);
            lancamento(dono, "-40.00", alimentacao, itau);
            lancamento(dono, "-10.00", alimentacao, null);

            var comImportado = transactions.findSharedInWindow(dono.getId(), INICIO, FIM,
                    Set.of(), Set.of(), true);
            var semImportado = transactions.findSharedInWindow(dono.getId(), INICIO, FIM,
                    Set.of(), Set.of(), false);

            assertThat(comImportado).hasSize(3);
            assertThat(semImportado).extracting(BankTransaction::getAccountId)
                    .containsExactlyInAnyOrder(nubank, itau);
        }

        @Test
        @DisplayName("Usuário de fora do grupo não recebe nada — e nada dele entra")
        void usuarioDeForaNaoRecebeNada() {
            lancamento(dono, "-100.00", alimentacao, null);
            lancamento(estranho, "-999.00", alimentacao, null);

            var doDono = transactions.findSharedInWindow(dono.getId(), INICIO, FIM,
                    Set.of(), Set.of(), true);
            var totaisDoEstranho = transactions.sumByCategoryShared(estranho.getId(), INICIO, FIM,
                    Set.of(), Set.of(), true);

            // A visão da casa é uma consulta por MEMBRO, pelo user_id dele:
            // quem não é membro nunca é consultado, e a linha do vizinho nunca
            // aparece na consulta de ninguém
            assertThat(doDono).hasSize(1);
            assertThat(doDono.get(0).getAmount()).isEqualByComparingTo("-100.00");
            assertThat(somaDe(totaisDoEstranho)).isEqualByComparingTo("-999.00");
        }

        @Test
        @DisplayName("Sem filtro nenhum, a soma é a da análise pessoal — e a perna interna fica fora igual")
        void semFiltroBateComAPessoal() {
            lancamento(dono, "-100.00", alimentacao, nubank);
            lancamento(dono, "4400.00", null, null);
            BankTransaction interna = lancamento(dono, "-500.00", null, null);
            interna.setInternalTransfer(true);
            transactions.saveAndFlush(interna);

            var pessoal = transactions.sumByCategory(dono.getId(), INICIO, FIM);
            var casa = transactions.sumByCategoryShared(dono.getId(), INICIO, FIM,
                    Set.of(), Set.of(), true);

            // O chamador vê a si mesmo pela consulta compartilhada com filtros
            // vazios: se ela divergisse da pessoal, "Eu" e "Casa" mostrariam
            // números diferentes para as mesmas linhas
            assertThat(somaDe(casa)).isEqualByComparingTo(somaDe(pessoal));
            assertThat(somaDe(casa)).isEqualByComparingTo("4300.00");
        }

        @Test
        @DisplayName("Todos os filtros juntos: só o que sobra dos três")
        void filtrosCombinados() {
            lancamento(dono, "-100.00", alimentacao, nubank);   // fica
            lancamento(dono, "-300.00", saude, nubank);         // categoria oculta
            lancamento(dono, "-40.00", alimentacao, itau);      // conta não compartilhada
            lancamento(dono, "-10.00", alimentacao, null);      // sem conta, importado desmarcado

            var linhas = transactions.findSharedInWindow(dono.getId(), INICIO, FIM,
                    Set.of(saude), Set.of(nubank), false);

            assertThat(linhas).hasSize(1);
            assertThat(linhas.get(0).getAmount()).isEqualByComparingTo("-100.00");
        }

        @Test
        @DisplayName("Linha SEM conta com categoria oculta não entra pelo 'extrato importado' — os filtros são E, não OU")
        void semContaComCategoriaOcultaNaoEntraPeloImportado() {
            lancamento(dono, "-100.00", alimentacao, null);   // sem conta, categoria visível: fica
            lancamento(dono, "-300.00", saude, null);         // sem conta, categoria OCULTA: sai
            lancamento(dono, "-50.00", alimentacao, nubank);  // conta compartilhada: fica
            lancamento(dono, "-70.00", saude, nubank);        // conta compartilhada, categoria oculta: sai
            lancamento(dono, "-40.00", alimentacao, itau);    // conta não compartilhada: sai

            var linhas = transactions.findSharedInWindow(dono.getId(), INICIO, FIM,
                    Set.of(saude), Set.of(nubank), true);
            var totais = transactions.sumByCategoryShared(dono.getId(), INICIO, FIM,
                    Set.of(saude), Set.of(nubank), true);

            // O caso frágil da cláusula: o ramo "sem conta e importado marcado"
            // ADMITE a linha de R$ 300, e a de categoria tem que barrá-la mesmo
            // assim. Um OU no lugar errado deixaria a categoria oculta voltar
            // por toda linha de upload manual — justamente as que não têm conta
            // para o filtro de contas pegar.
            assertThat(linhas).extracting(BankTransaction::getAmount)
                    .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .containsExactlyInAnyOrder(new BigDecimal("-100.00"), new BigDecimal("-50.00"));
            assertThat(totais).extracting(BankTransactionRepository.CategoryTotal::getCategoryId)
                    .containsOnly(alimentacao);
            assertThat(somaDe(totais)).isEqualByComparingTo("-150.00");
        }

        @Test
        @DisplayName("A janela é fechada no início e aberta no fim, como na pessoal")
        void janelaFechadaNoInicioAbertaNoFim() {
            BankTransaction naVirada = lancamento(dono, "-20.00", alimentacao, null);
            naVirada.setDate(FIM);
            transactions.saveAndFlush(naVirada);
            lancamento(dono, "-10.00", alimentacao, null);

            var linhas = transactions.findSharedInWindow(dono.getId(), INICIO, FIM,
                    Set.of(), Set.of(), true);

            assertThat(linhas).hasSize(1);
            assertThat(linhas.get(0).getAmount()).isEqualByComparingTo("-10.00");
        }
    }

    // ------------------------------------------------- as tabelas da casa

    @Nested
    @DisplayName("Grupo, membro e convite")
    class Tabelas {

        private FamilyGroup casa;

        @BeforeEach
        void criaCasa() {
            casa = groups.save(FamilyGroup.builder().name("Casa").owner(dono).build());
        }

        private FamilyMember membro(User user, FamilyMember.Role role) {
            return members.save(FamilyMember.builder()
                    .group(casa)
                    .user(user)
                    .role(role)
                    .shareScope(FamilyMember.ShareScope.TOTALS)
                    .build());
        }

        @Test
        @DisplayName("Um grupo por usuário: a segunda linha do mesmo user_id não entra")
        void umGrupoPorUsuario() {
            membro(dono, FamilyMember.Role.OWNER);
            FamilyGroup outra = groups.save(FamilyGroup.builder().name("Outra").owner(estranho).build());

            // saveAndFlush pelo repositório, e não em.flush(): é o proxy do
            // Spring Data que traduz a violação para a exceção do Spring
            assertThatThrownBy(() -> members.saveAndFlush(FamilyMember.builder()
                    .group(outra).user(dono).role(FamilyMember.Role.MEMBER)
                    .shareScope(FamilyMember.ShareScope.TOTALS).build()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Os parâmetros do membro vão e voltam do banco, e o padrão é TOTALS com importado marcado")
        void parametrosVaoEVoltam() {
            FamilyMember salvo = membro(dono, FamilyMember.Role.OWNER);
            salvo.getHiddenCategoryIds().add(saude);
            salvo.getSharedAccountIds().add(nubank);
            members.saveAndFlush(salvo);
            em.clear();

            FamilyMember lido = members.findByUserId(dono.getId()).orElseThrow();

            assertThat(lido.getShareScope()).isEqualTo(FamilyMember.ShareScope.TOTALS);
            assertThat(lido.isIncludeUnassigned()).isTrue();
            assertThat(lido.getHiddenCategoryIds()).containsExactly(saude);
            assertThat(lido.getSharedAccountIds()).containsExactly(nubank);
        }

        @Test
        @DisplayName("Remover o membro apaga os parâmetros dele — nada fica lembrado")
        void removerMembroApagaParametros() {
            FamilyMember salvo = membro(dono, FamilyMember.Role.OWNER);
            salvo.getHiddenCategoryIds().add(saude);
            salvo.getSharedAccountIds().add(nubank);
            members.saveAndFlush(salvo);

            members.delete(salvo);
            em.flush();

            assertThat(contar("family_member_hidden_categories")).isZero();
            assertThat(contar("family_member_shared_accounts")).isZero();
        }

        @Test
        @DisplayName("O membro de OUTRA casa não é alcançado pela minha")
        void membroDeOutraCasaNaoEAlcancado() {
            FamilyGroup outra = groups.save(FamilyGroup.builder().name("Outra").owner(estranho).build());
            FamilyMember deFora = members.save(FamilyMember.builder()
                    .group(outra).user(estranho).role(FamilyMember.Role.OWNER)
                    .shareScope(FamilyMember.ShareScope.TOTALS).build());

            // A rota de remoção responde 404 com isto vazio — o id de membro de
            // outra casa não existe para quem pede
            assertThat(members.findByIdAndGroupId(deFora.getId(), casa.getId())).isEmpty();
            assertThat(members.findByIdAndGroupId(deFora.getId(), outra.getId())).isPresent();
        }

        @Test
        @DisplayName("O convite vivo é o não usado e não vencido; usado e vencido ficam de fora")
        void conviteVivo() {
            OffsetDateTime agora = OffsetDateTime.now(ZoneOffset.UTC);
            convite("usado", agora.plusDays(7), agora.minusHours(1));
            convite("vencido", agora.minusMinutes(1), null);
            FamilyInvite vivo = convite("vivo", agora.plusDays(7), null);

            var achado = invites.findFirstByGroupIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                    casa.getId(), agora);

            assertThat(achado).get().extracting(FamilyInvite::getId).isEqualTo(vivo.getId());
            assertThat(invites.findByCodeHash("usado")).isPresent();
        }

        @Test
        @DisplayName("O aceite é de uso único: a primeira reivindicação grava used_at, a segunda não acha linha")
        void aceiteDeUsoUnico() {
            OffsetDateTime agora = OffsetDateTime.now(ZoneOffset.UTC);
            FamilyInvite vivo = convite("vivo", agora.plusDays(7), null);
            FamilyInvite vencido = convite("vencido", agora.minusMinutes(1), null);
            em.flush();

            int primeira = invites.claim(vivo.getId(), agora);
            int segunda = invites.claim(vivo.getId(), agora.plusSeconds(1));
            int doVencido = invites.claim(vencido.getId(), agora);
            em.clear();

            // É esta condição no UPDATE — e não um set + save — que segura dois
            // joins simultâneos com o mesmo código: o segundo reavalia o WHERE
            // depois do lock e não encontra used_at nulo. O vencido tampouco
            // é aceitável, mesmo sem uso.
            assertThat(primeira).isEqualTo(1);
            assertThat(segunda).isZero();
            assertThat(doVencido).isZero();
            assertThat(invites.findById(vivo.getId())).get()
                    .extracting(FamilyInvite::getUsedAt).isNotNull();
            assertThat(invites.findById(vencido.getId())).get()
                    .extracting(FamilyInvite::getUsedAt).isNull();
        }

        @Test
        @DisplayName("Emitir de novo apaga só o não usado: o usado é histórico")
        void emitirDeNovoApagaSoONaoUsado() {
            OffsetDateTime agora = OffsetDateTime.now(ZoneOffset.UTC);
            convite("usado", agora.plusDays(7), agora.minusHours(1));
            convite("vivo", agora.plusDays(7), null);

            invites.deleteByGroupIdAndUsedAtIsNull(casa.getId());
            em.flush();
            em.clear();

            assertThat(invites.findAllByGroupId(casa.getId()))
                    .extracting(FamilyInvite::getCodeHash)
                    .containsExactly("usado");
        }

        private FamilyInvite convite(String hash, OffsetDateTime expira, OffsetDateTime usadoEm) {
            return invites.save(FamilyInvite.builder()
                    .group(casa)
                    .createdBy(dono)
                    .codeHash(hash)
                    .expiresAt(expira)
                    .usedAt(usadoEm)
                    .build());
        }

        private long contar(String tabela) {
            Number n = (Number) em.getEntityManager()
                    .createNativeQuery("select count(*) from " + tabela)
                    .getSingleResult();
            return n.longValue();
        }
    }
}
