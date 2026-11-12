package br.com.economize.service.family;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.FamilyGroup;
import br.com.economize.model.FamilyMember;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.FamilyGroupRepository;
import br.com.economize.repository.FamilyMemberRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A varredura da casa contra o JPA de verdade — e não contra entidades montadas
 * à mão.
 *
 * <p>Este teste existe por um defeito real: o teste de unidade passava e a
 * produção respondia 500. O vínculo do membro traz {@code group} e {@code user}
 * como proxies LAZY, e o nome do OUTRO membro — que é o sinal inteiro da
 * varredura — só pode ser lido com a sessão aberta. Com entidades construídas
 * pelo builder não há proxy nenhum, então o teste de unidade nunca tocaria
 * nisso. Aqui as entidades vêm do banco, e o carregamento preguiçoso acontece.
 */
@DataJpaTest
@Import(FamilyTransferService.class)
// SEM transação de teste: é esta linha que faz o teste valer. Com a transação
// que o @DataJpaTest abre por padrão, a sessão fica aberta durante o método
// inteiro e QUALQUER proxy carrega — o serviço passaria mesmo sem abrir a sua
// própria transação, que foi exatamente o que aconteceu em produção
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Varredura da casa contra o JPA (V28)")
class FamilyTransferPersistenceTest {

    private static final OffsetDateTime AGOSTO =
            OffsetDateTime.of(2026, 8, 7, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private FamilyTransferService service;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FamilyGroupRepository groupRepository;

    @Autowired
    private FamilyMemberRepository memberRepository;

    @Autowired
    private BankTransactionRepository bankTransactionRepository;

    private User marido;
    private User esposa;

    @BeforeEach
    void setUp() {
        marido = userRepository.save(User.builder()
                .name("Neemias Cormino Manso").email("dono@economize.test").password("x").build());
        esposa = userRepository.save(User.builder()
                .name("Alice dos Santos Araujo").email("esposa@economize.test").password("x").build());
        FamilyGroup casa = groupRepository.save(FamilyGroup.builder()
                .name("Casa Salada").owner(marido).build());
        memberRepository.save(FamilyMember.builder()
                .group(casa).user(marido).role(FamilyMember.Role.OWNER)
                .shareScope(FamilyMember.ShareScope.TRANSACTIONS).build());
        memberRepository.save(FamilyMember.builder()
                .group(casa).user(esposa).role(FamilyMember.Role.MEMBER)
                .shareScope(FamilyMember.ShareScope.TRANSACTIONS).build());
    }

    // Sem a transação com rollback do @DataJpaTest (desligada acima), cada
    // teste deixa as linhas no H2; sem limpar, o segundo teste esbarra no
    // e-mail único do primeiro
    @AfterEach
    void tearDown() {
        bankTransactionRepository.deleteAll();
        memberRepository.deleteAll();
        groupRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("o Pix para a esposa é marcado, com as entidades vindas do banco")
    void shouldMarkWithLazyEntitiesLoadedFromTheDatabase() {
        BankTransaction paraEla = save("-387.11", "Pix enviado: Cp — Alice dos Santos Araujo");
        BankTransaction mercado = save("-92.40", "Compra no debito: SUPERMERCADO ARAUJO");
        FamilyTransferService.Outcome resultado = service.reconcile(marido.getEmail());

        assertThat(resultado.against()).isEqualTo(1);
        assertThat(resultado.marked()).isEqualTo(1);
        assertThat(bankTransactionRepository.findById(paraEla.getId()).orElseThrow()
                .isFamilyTransfer()).isTrue();
        assertThat(bankTransactionRepository.findById(mercado.getId()).orElseThrow()
                .isFamilyTransfer()).isFalse();
    }

    @Test
    @DisplayName("sair da casa solta as marcas SÓ nas linhas de quem saiu")
    void clearAllShouldTouchOnlyTheLeaversRows() {
        BankTransaction paraEla = save("-387.11", "Pix enviado: Cp — Alice dos Santos Araujo");
        BankTransaction dela = save(esposa, "-50.00", "Pix enviado: Neemias Cormino Manso");
        bankTransactionRepository.markAsFamilyTransfer(marido.getId(), java.util.List.of(paraEla.getId()));
        bankTransactionRepository.markAsFamilyTransfer(esposa.getId(), java.util.List.of(dela.getId()));

        int soltas = bankTransactionRepository.clearAllFamilyTransfers(marido.getId());

        assertThat(soltas).isEqualTo(1);
        assertThat(bankTransactionRepository.findById(paraEla.getId()).orElseThrow()
                .isFamilyTransfer()).isFalse();
        // a linha da esposa, que segue na casa, não é tocada
        assertThat(bankTransactionRepository.findById(dela.getId()).orElseThrow()
                .isFamilyTransfer()).isTrue();
    }

    @Test
    @DisplayName("a varredura em transação nova enxerga o vínculo já gravado e marca o Pix")
    void reconcileInNewTransactionShouldMark() {
        BankTransaction paraEla = save("-387.11", "Pix enviado: Cp — Alice dos Santos Araujo");

        FamilyTransferService.Outcome resultado = service.reconcileInNewTransaction(marido.getEmail());

        assertThat(resultado.marked()).isEqualTo(1);
        assertThat(bankTransactionRepository.findById(paraEla.getId()).orElseThrow()
                .isFamilyTransfer()).isTrue();
    }

    private BankTransaction save(String valor, String descricao) {
        return save(marido, valor, descricao);
    }

    private BankTransaction save(User dono, String valor, String descricao) {
        return bankTransactionRepository.save(BankTransaction.builder()
                .user(dono)
                .transactionId(descricao + valor)
                .type(new BigDecimal(valor).signum() < 0 ? "DEBIT" : "CREDIT")
                .amount(new BigDecimal(valor))
                .description(descricao)
                .date(AGOSTO)
                .internalTransfer(false)
                .ignored(false)
                .familyTransfer(false)
                .build());
    }
}
