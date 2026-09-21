package br.com.economize.service;

import br.com.economize.dto.account.AccountResponse;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.User;
import br.com.economize.repository.ConnectorAccountRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O registro das origens (EC-113): o upsert que roda a cada sincronização, o
 * saneamento do que vem de terceiro, o 404 do dono errado e as duas situações em
 * que a conta "já existe" sem que o id do provedor diga isso — a corrida entre
 * duas syncs e o revínculo da instituição.
 */
@ExtendWith(MockitoExtension.class)
class ConnectorAccountServiceTest {

    private static final String EMAIL = "teste@economize.app";

    @Mock
    private ConnectorAccountRepository accountRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ConnectorAccountService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Teste").password("x").build();
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    // ------------------------------------------------- upsert

    @Test
    @DisplayName("conta nova é inserida com o dono e a origem do provedor")
    void registerInsertsUnknownAccount() {
        noKnownAccount("acc-card");
        noOrphans();
        when(accountRepository.saveAndFlush(any(ConnectorAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        ConnectorAccount saved = service.register(user, snapshot("acc-card", "Ultravioleta ····1234",
                "Nubank", ConnectorAccount.AccountType.CREDIT_CARD, 10, 17));

        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getProviderAccountId()).isEqualTo("acc-card");
        assertThat(saved.getName()).isEqualTo("Ultravioleta ····1234");
        assertThat(saved.getInstitution()).isEqualTo("Nubank");
        assertThat(saved.getStatementClosingDay()).isEqualTo(10);
        assertThat(saved.getStatementDueDay()).isEqualTo(17);
    }

    @Test
    @DisplayName("conta já conhecida é ATUALIZADA no lugar: o id interno que os lançamentos referenciam não muda")
    void registerUpdatesKnownAccountKeepingTheInternalId() {
        ConnectorAccount existing = account("acc-card", "Nome antigo", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, 5, 12);
        UUID internalId = existing.getId();
        when(accountRepository.findByUserIdAndProviderAccountId(user.getId(), "acc-card"))
                .thenReturn(Optional.of(existing));
        when(accountRepository.save(any(ConnectorAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // o banco mudou o vencimento e o nome comercial do cartão
        ConnectorAccount saved = service.register(user, snapshot("acc-card", "Ultravioleta ····1234",
                "Nubank", ConnectorAccount.AccountType.CREDIT_CARD, 10, 17));

        assertThat(saved.getId()).isEqualTo(internalId);
        assertThat(saved.getName()).isEqualTo("Ultravioleta ····1234");
        assertThat(saved.getStatementClosingDay()).isEqualTo(10);
        assertThat(saved.getStatementDueDay()).isEqualTo(17);
        verify(accountRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------- saneamento do que vem de fora

    @Test
    @DisplayName("dia de fechamento/vencimento fora de 1..31 é DESCARTADO — a fatura cai no ciclo do calendário")
    void invalidStatementDaysAreDropped() {
        noKnownAccount("acc-card");
        noOrphans();
        when(accountRepository.saveAndFlush(any(ConnectorAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // 0 e 45 vêm de campo de terceiro; gravados, estourariam o recorte do
        // ciclo meses depois, num lugar sem nenhuma pista da origem do defeito
        ConnectorAccount comLixo = service.register(user, snapshot("acc-card", "Cartão", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, 0, 45));
        assertThat(comLixo.getStatementClosingDay()).isNull();
        assertThat(comLixo.getStatementDueDay()).isNull();

        noKnownAccount("acc-2");
        ConnectorAccount semMetadado = service.register(user, snapshot("acc-2", "Cartão", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, null, null));
        assertThat(semMetadado.getStatementClosingDay()).isNull();

        // e os extremos válidos passam intactos
        noKnownAccount("acc-3");
        ConnectorAccount valido = service.register(user, snapshot("acc-3", "Cartão", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, 1, 31));
        assertThat(valido.getStatementClosingDay()).isEqualTo(1);
        assertThat(valido.getStatementDueDay()).isEqualTo(31);
    }

    @Test
    @DisplayName("rótulo e instituição são aparados ao tamanho da coluna: nome comprido não derruba a sync")
    void nameAndInstitutionAreTruncatedToColumnSize() {
        noKnownAccount("acc-card");
        noOrphans();
        when(accountRepository.saveAndFlush(any(ConnectorAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        ConnectorAccount saved = service.register(user, snapshot("acc-card",
                "N".repeat(200), "I".repeat(200), ConnectorAccount.AccountType.BANK, null, null));

        assertThat(saved.getName()).hasSize(120);
        assertThat(saved.getInstitution()).hasSize(160);
    }

    // ------------------------------------------------- corrida entre duas syncs

    @Test
    @DisplayName("duas syncs simultâneas: quem perde o unique REAPROVEITA a linha do vencedor, sem derrubar a sync")
    void insertRaceReusesTheWinnersRow() {
        ConnectorAccount doVencedor = account("acc-card", "Ultravioleta ····1234", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, 10, 17);
        when(accountRepository.findByUserIdAndProviderAccountId(user.getId(), "acc-card"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(doVencedor));
        noOrphans();
        when(accountRepository.saveAndFlush(any(ConnectorAccount.class)))
                .thenThrow(new DataIntegrityViolationException("uq_connector_accounts_provider"));

        ConnectorAccount saved = service.register(user, snapshot("acc-card", "Ultravioleta ····1234",
                "Nubank", ConnectorAccount.AccountType.CREDIT_CARD, 10, 17));

        assertThat(saved).isSameAs(doVencedor);
    }

    @Test
    @DisplayName("unique violado sem linha correspondente no re-find propaga o erro: não é a corrida conhecida")
    void insertRaceRethrowsWhenNothingIsFound() {
        when(accountRepository.findByUserIdAndProviderAccountId(user.getId(), "acc-card"))
                .thenReturn(Optional.empty());
        noOrphans();
        when(accountRepository.saveAndFlush(any(ConnectorAccount.class)))
                .thenThrow(new DataIntegrityViolationException("outra constraint qualquer"));

        assertThatThrownBy(() -> service.register(user, snapshot("acc-card", "Cartão", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------- revínculo da instituição

    @Test
    @DisplayName("REVÍNCULO: a origem órfã é readotada com o id novo do provedor — o cartão não vira dois")
    void rebindAdoptsTheOrphanInsteadOfDuplicating() {
        // o usuário desvinculou o Nubank (pluggyItemId nulo, histórico intacto) e
        // reconectou: o widget criou item novo e o Pluggy devolveu id de conta novo
        ConnectorAccount orfa = account(null, "Ultravioleta ····1234", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, 10, 17);
        orfa.setProviderAccountId("acc-card-antigo");
        orfa.setPluggyItemId(null);
        UUID internalId = orfa.getId();
        noKnownAccount("acc-card-novo");
        when(accountRepository.findAllByUserIdAndPluggyItemIdIsNull(user.getId())).thenReturn(List.of(orfa));
        when(accountRepository.save(any(ConnectorAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID itemNovo = UUID.randomUUID();
        ConnectorAccount saved = service.register(user, new ConnectorAccountService.AccountSnapshot(
                "acc-card-novo", "Ultravioleta ····1234", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, 10, 17, itemNovo));

        // MESMA linha: o histórico inteiro continua apontando para este id, e a
        // fatura não fica cortada ao meio na data da revinculação
        assertThat(saved.getId()).isEqualTo(internalId);
        assertThat(saved.getProviderAccountId()).isEqualTo("acc-card-novo");
        assertThat(saved.getPluggyItemId()).isEqualTo(itemNovo);
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("O limite que o provedor entrega preenche o cartão sem limite — e nunca sobrescreve o declarado")
    void connectorCreditLimitFillsOnlyWhatIsEmpty() {
        // O Pluggy entrega o limite total do Inter (R$ 5.330,01 medidos na
        // conta do dono em 17/09/2026); o app perguntava ao usuário o que o
        // banco já dizia. Mas quem declarou o próprio limite decidiu, e
        // decisão de gente vence leitura de conector
        ConnectorAccount semLimite = account("acc-card", "GOLD ····8210", "Banco Inter",
                ConnectorAccount.AccountType.CREDIT_CARD, null, null);
        when(accountRepository.findByUserIdAndProviderAccountId(user.getId(), "acc-card"))
                .thenReturn(Optional.of(semLimite));
        when(accountRepository.save(any(ConnectorAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        ConnectorAccount saved = service.register(user, new ConnectorAccountService.AccountSnapshot(
                "acc-card", "GOLD ····8210", "Banco Inter", ConnectorAccount.AccountType.CREDIT_CARD,
                null, 10, semLimite.getPluggyItemId(), null, null, new java.math.BigDecimal("5330.01")));
        assertThat(saved.getCreditLimit()).isEqualByComparingTo("5330.01");

        // declarado à mão: a leitura seguinte do conector não mexe
        saved.setCreditLimit(new java.math.BigDecimal("6000"));
        ConnectorAccount depois = service.register(user, new ConnectorAccountService.AccountSnapshot(
                "acc-card", "GOLD ····8210", "Banco Inter", ConnectorAccount.AccountType.CREDIT_CARD,
                null, 10, semLimite.getPluggyItemId(), null, null, new java.math.BigDecimal("5330.01")));
        assertThat(depois.getCreditLimit()).isEqualByComparingTo("6000");

        // e quem divide o limite de outro cartão continua dividindo
        depois.setCreditLimit(null);
        depois.setCreditLimitSharedWith(UUID.randomUUID());
        ConnectorAccount compartilhado = service.register(user, new ConnectorAccountService.AccountSnapshot(
                "acc-card", "GOLD ····8210", "Banco Inter", ConnectorAccount.AccountType.CREDIT_CARD,
                null, 10, semLimite.getPluggyItemId(), null, null, new java.math.BigDecimal("5330.01")));
        assertThat(compartilhado.getCreditLimit()).isNull();
        assertThat(compartilhado.getCreditLimitSharedWith()).isNotNull();
    }

    @Test
    @DisplayName("DUAS órfãs com o mesmo rótulo são ambíguas: cria origem nova em vez de adivinhar qual é qual")
    void rebindRefusesToGuessBetweenTwoIdenticalOrphans() {
        ConnectorAccount uma = account("acc-a", "Cartão", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, null, null);
        uma.setPluggyItemId(null);
        ConnectorAccount outra = account("acc-b", "Cartão", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, null, null);
        outra.setPluggyItemId(null);
        noKnownAccount("acc-novo");
        when(accountRepository.findAllByUserIdAndPluggyItemIdIsNull(user.getId())).thenReturn(List.of(uma, outra));
        when(accountRepository.saveAndFlush(any(ConnectorAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        ConnectorAccount saved = service.register(user, snapshot("acc-novo", "Cartão", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, null, null));

        // misturar o histórico de dois cartões é pior do que a duplicata
        assertThat(saved.getProviderAccountId()).isEqualTo("acc-novo");
        verify(accountRepository).saveAndFlush(any(ConnectorAccount.class));
    }

    @Test
    @DisplayName("origem AINDA VINCULADA nunca é readotada: conta que recebe sync não é um reencontro")
    void rebindIgnoresStillLinkedAccounts() {
        noKnownAccount("acc-novo");
        // a consulta já filtra por pluggy_item_id nulo; nenhuma candidata volta
        noOrphans();
        when(accountRepository.saveAndFlush(any(ConnectorAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        ConnectorAccount saved = service.register(user, snapshot("acc-novo", "Ultravioleta ····1234",
                "Nubank", ConnectorAccount.AccountType.CREDIT_CARD, 10, 17));

        assertThat(saved.getProviderAccountId()).isEqualTo("acc-novo");
        verify(accountRepository).saveAndFlush(any(ConnectorAccount.class));
    }

    @Test
    @DisplayName("órfã de OUTRA instituição, outro rótulo ou outro tipo não é readotada")
    void rebindRequiresInstitutionNameAndTypeToMatch() {
        ConnectorAccount outroBanco = account("acc-a", "Ultravioleta ····1234", "Banco Inter",
                ConnectorAccount.AccountType.CREDIT_CARD, null, null);
        outroBanco.setPluggyItemId(null);
        ConnectorAccount outroRotulo = account("acc-b", "Ultravioleta ····9999", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, null, null);
        outroRotulo.setPluggyItemId(null);
        ConnectorAccount outroTipo = account("acc-c", "Ultravioleta ····1234", "Nubank",
                ConnectorAccount.AccountType.BANK, null, null);
        outroTipo.setPluggyItemId(null);
        noKnownAccount("acc-novo");
        when(accountRepository.findAllByUserIdAndPluggyItemIdIsNull(user.getId()))
                .thenReturn(List.of(outroBanco, outroRotulo, outroTipo));
        when(accountRepository.saveAndFlush(any(ConnectorAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        service.register(user, snapshot("acc-novo", "Ultravioleta ····1234", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, 10, 17));

        verify(accountRepository).saveAndFlush(any(ConnectorAccount.class));
    }

    // ------------------------------------------------- dono e listagem

    @Test
    @DisplayName("conta de outro usuário responde 404, nunca 403: o dono é filtro da consulta")
    void requireOwnedHidesForeignAccounts() {
        UUID alheia = UUID.randomUUID();
        when(accountRepository.findByIdAndUserId(alheia, user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireOwned(alheia, user.getId()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Conta não encontrada");
    }

    @Test
    @DisplayName("conta do próprio usuário é devolvida pelo requireOwned")
    void requireOwnedReturnsTheUsersAccount() {
        ConnectorAccount minha = account("acc-card", "Ultravioleta ····1234", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, 10, 17);
        when(accountRepository.findByIdAndUserId(minha.getId(), user.getId())).thenReturn(Optional.of(minha));

        assertThat(service.requireOwned(minha.getId(), user.getId())).isSameAs(minha);
    }

    @Test
    @DisplayName("listagem expõe o vínculo: conta de conexão desvinculada vem com linked=false")
    void listExposesTheLinkFlag() {
        ConnectorAccount vinculada = account("acc-card", "Ultravioleta ····1234", "Nubank",
                ConnectorAccount.AccountType.CREDIT_CARD, 10, 17);
        ConnectorAccount orfa = account("acc-old", "Conta Corrente ····3456", "Banco Inter",
                ConnectorAccount.AccountType.BANK, null, null);
        orfa.setPluggyItemId(null);
        when(accountRepository.findAllByUserIdOrderByNameAsc(user.getId()))
                .thenReturn(List.of(vinculada, orfa));

        List<AccountResponse> list = service.list(EMAIL);

        assertThat(list).extracting(AccountResponse::linked).containsExactly(true, false);
        assertThat(list).extracting(AccountResponse::name)
                .containsExactly("Ultravioleta ····1234", "Conta Corrente ····3456");
    }

    @Test
    @DisplayName("listagem de e-mail inexistente falha antes de tocar o repositório de contas")
    void listRequiresAnExistingUser() {
        when(userRepository.findByEmail("fantasma@economize.app")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list("fantasma@economize.app"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usuário não encontrado");

        verify(accountRepository, never()).findAllByUserIdOrderByNameAsc(any());
    }

    // ------------------------------------------------- apoio

    private void noKnownAccount(String providerAccountId) {
        when(accountRepository.findByUserIdAndProviderAccountId(user.getId(), providerAccountId))
                .thenReturn(Optional.empty());
    }

    private void noOrphans() {
        lenient().when(accountRepository.findAllByUserIdAndPluggyItemIdIsNull(user.getId()))
                .thenReturn(List.of());
    }

    private ConnectorAccountService.AccountSnapshot snapshot(String providerAccountId, String name,
                                                             String institution,
                                                             ConnectorAccount.AccountType type,
                                                             Integer closingDay, Integer dueDay) {
        return new ConnectorAccountService.AccountSnapshot(providerAccountId, name, institution, type,
                closingDay, dueDay, UUID.randomUUID());
    }

    private ConnectorAccount account(String providerAccountId, String name, String institution,
                                     ConnectorAccount.AccountType type, Integer closingDay, Integer dueDay) {
        return ConnectorAccount.builder()
                .id(UUID.randomUUID())
                .user(user)
                .pluggyItemId(UUID.randomUUID())
                .providerAccountId(providerAccountId)
                .name(name)
                .institution(institution)
                .type(type)
                .statementClosingDay(closingDay)
                .statementDueDay(dueDay)
                .build();
    }

    // ------------------------------------------------- saldo declarado no arquivo

    /**
     * O saldo que vem dentro do OFX (EC: "3.021,06 não existe", 15/09/2026).
     *
     * <p>Antes disto, TODO saldo do produto nascia da soma dos lançamentos
     * importados — e soma de movimento não é saldo. O dono viu o resultado em
     * dois lugares no mesmo dia: a Home anunciando três mil reais que não
     * existiam, e a previsão dizendo que ele fecharia o mês devendo dezenove
     * mil. O dado sempre esteve no arquivo, no bloco LEDGERBAL.
     */
    @Test
    @DisplayName("saldo do arquivo é gravado quando a conta ainda não tinha nenhum")
    void recordStatementBalanceGravaOPrimeiro() {
        ConnectorAccount conta = ConnectorAccount.builder()
                .id(UUID.randomUUID()).user(user).providerAccountId("manual-1")
                .name("Inter").type(ConnectorAccount.AccountType.BANK).build();
        when(accountRepository.save(any(ConnectorAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        BigDecimal gravado = service.recordStatementBalance(
                conta, new BigDecimal("4213.83"), OffsetDateTime.parse("2026-09-15T12:00:00Z"));

        assertThat(gravado).isEqualByComparingTo("4213.83");
        assertThat(conta.getReportedBalance()).isEqualByComparingTo("4213.83");
        assertThat(conta.getReportedBalanceAt()).isEqualTo(OffsetDateTime.parse("2026-09-15T12:00:00Z"));
    }

    /**
     * Reimportar arquivo velho é rotina em app de finanças — o usuário baixa
     * seis meses de extrato e sobe tudo. Se o último arquivo subido mandasse
     * no saldo, importar março faria o app achar que o saldo de março é o de
     * hoje. Quem manda é a DATA da leitura, nunca a ordem de chegada.
     */
    @Test
    @DisplayName("saldo mais VELHO que o guardado não sobrescreve")
    void recordStatementBalanceIgnoraLeituraVelha() {
        ConnectorAccount conta = ConnectorAccount.builder()
                .id(UUID.randomUUID()).user(user).providerAccountId("manual-1")
                .name("Inter").type(ConnectorAccount.AccountType.BANK)
                .reportedBalance(new BigDecimal("900.00"))
                .reportedBalanceAt(OffsetDateTime.parse("2026-09-15T12:00:00Z"))
                .build();

        BigDecimal gravado = service.recordStatementBalance(
                conta, new BigDecimal("120.00"), OffsetDateTime.parse("2026-03-31T12:00:00Z"));

        assertThat(gravado).isNull();
        assertThat(conta.getReportedBalance()).isEqualByComparingTo("900.00");
        verify(accountRepository, never()).save(any(ConnectorAccount.class));
    }

    // ------------------------------------------------- limite de cartão

    @Test
    @DisplayName("limite informado entra no cartão")
    void declareCreditLimitGuardaOValor() {
        UUID id = UUID.randomUUID();
        ConnectorAccount cartao = cartaoDe(id);
        when(accountRepository.findByIdAndUserId(id, user.getId())).thenReturn(Optional.of(cartao));
        when(accountRepository.save(any(ConnectorAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        ConnectorAccount salvo = service.declareCreditLimit(EMAIL, id, new BigDecimal("5000"), null);

        assertThat(salvo.getCreditLimit()).isEqualByComparingTo("5000");
        assertThat(salvo.getCreditLimitSharedWith()).isNull();
    }

    /**
     * Cartão virtual e adicional consomem a MESMA bolsa. Guardar valor nos dois
     * faria a soma de crédito disponível mostrar o dobro do que existe — por
     * isso quem aponta para outro cartão não guarda valor próprio.
     */
    @Test
    @DisplayName("cartão que divide a bolsa de outro não guarda limite próprio")
    void declareCreditLimitCompartilhadoZeraOValorProprio() {
        UUID id = UUID.randomUUID();
        UUID donoId = UUID.randomUUID();
        ConnectorAccount cartao = cartaoDe(id);
        cartao.setCreditLimit(new BigDecimal("1000"));
        when(accountRepository.findByIdAndUserId(id, user.getId())).thenReturn(Optional.of(cartao));
        when(accountRepository.findByIdAndUserId(donoId, user.getId())).thenReturn(Optional.of(cartaoDe(donoId)));
        when(accountRepository.save(any(ConnectorAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        ConnectorAccount salvo = service.declareCreditLimit(EMAIL, id, new BigDecimal("1000"), donoId);

        assertThat(salvo.getCreditLimit()).isNull();
        assertThat(salvo.getCreditLimitSharedWith()).isEqualTo(donoId);
    }

    @Test
    @DisplayName("conta corrente não tem limite de cartão")
    void declareCreditLimitRecusaContaCorrente() {
        UUID id = UUID.randomUUID();
        ConnectorAccount conta = ConnectorAccount.builder()
                .id(id).user(user).providerAccountId("manual-2").name("Inter")
                .type(ConnectorAccount.AccountType.BANK).build();
        when(accountRepository.findByIdAndUserId(id, user.getId())).thenReturn(Optional.of(conta));

        assertThatThrownBy(() -> service.declareCreditLimit(EMAIL, id, new BigDecimal("100"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cartão");
    }

    @Test
    @DisplayName("ninguém divide limite consigo mesmo")
    void declareCreditLimitRecusaAutoReferencia() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findByIdAndUserId(id, user.getId())).thenReturn(Optional.of(cartaoDe(id)));

        assertThatThrownBy(() -> service.declareCreditLimit(EMAIL, id, null, id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Um degrau só. Corrente de ponteiros faria a soma de crédito depender da
     * ordem em que as contas são lidas — e ninguém consegue explicar isso em
     * uma frase na tela.
     */
    @Test
    @DisplayName("não dá para apontar para um cartão que já aponta para outro")
    void declareCreditLimitRecusaCorrente() {
        UUID id = UUID.randomUUID();
        UUID meioId = UUID.randomUUID();
        ConnectorAccount meio = cartaoDe(meioId);
        meio.setCreditLimitSharedWith(UUID.randomUUID());
        when(accountRepository.findByIdAndUserId(id, user.getId())).thenReturn(Optional.of(cartaoDe(id)));
        when(accountRepository.findByIdAndUserId(meioId, user.getId())).thenReturn(Optional.of(meio));

        assertThatThrownBy(() -> service.declareCreditLimit(EMAIL, id, null, meioId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dono do limite");
    }

    private ConnectorAccount cartaoDe(UUID id) {
        return ConnectorAccount.builder()
                .id(id).user(user).providerAccountId("card-" + id)
                .name("Cartão").type(ConnectorAccount.AccountType.CREDIT_CARD).build();
    }
}
