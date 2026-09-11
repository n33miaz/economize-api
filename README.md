# Economize! — API

Backend do **Economize!**, um aplicativo de finanças pessoais. Escrito em **Java 17** com
**Spring Boot 3.4** e **WebFlux**, ele guarda os dados, aplica as regras e é a única fonte de
número do produto: **nenhum total é calculado no aplicativo**, para que as telas nunca possam
discordar entre si.

- **Repositório do app:** [economize-app](https://github.com/n33miaz/economize-app)
- **Como o trabalho anda por aqui:** [CONTRIBUTING.md](CONTRIBUTING.md)
- **Como relatar uma falha de segurança:** [SECURITY.md](SECURITY.md)

---

## O que a API faz

O produto começou como agregador de cotações e virou outra coisa. Hoje o miolo é o **extrato**:

- **Importação de extrato** em CSV, OFX, PDF, XLSX e TXT, com um parser por layout de banco —
  os formatos brasileiros não combinam entre si, e tratar todos como "CSV" foi a primeira
  tentativa que não sobreviveu ao segundo banco.
- **Open Finance** pela Pluggy, opcional e desligado por padrão. Conta conectada e arquivo
  importado convivem na mesma lista, e cada lançamento diz por onde entrou.
- **Categorização** por vocabulário de regras, com fila de revisão para o que ficou em dúvida.
  O usuário corrige, e a correção vira padrão para as próximas.
- **Faxina depois de toda importação**: seis varreduras tiram das somas o que nunca foi
  movimento — dinheiro trocando entre contas do mesmo titular, aplicação e resgate, repasse
  dentro de casa, a mesma linha que entrou por duas portas e a compra que foi estornada. Cada
  passada deixa recado e pode ser desfeita.
- **Análise** por mês ou por ciclo de fatura ancorado no dia que o usuário escolher, com teto
  por categoria, totais por dia e projeção de parcelamentos.
- **Cartões**: faturas fechadas e a em aberto, reserva de dinheiro para uma fatura sem
  inventar lançamento no extrato.
- **Recorrências** detectadas do histórico, previsão de saldo e caça-assinaturas.
- **Casa**: duas pessoas compartilhando a leitura sem misturar os extratos.
- **Assistente** com IA, que só responde com os mesmos números que as telas mostram.
- **Mercado**: cotações, índices e notícias, com cache e _fallback_ entre provedores — a parte
  mais antiga do projeto, hoje acessória.

A referência viva dos endpoints é o Swagger; a lista acima envelhece, ele não.

## Decisões que explicam o código

- **WebFlux com JPA bloqueante**: cada acesso a banco roda em `Mono.fromCallable` sobre o
  `boundedElastic`. Misturar os dois exige disciplina, e é o preço de ter I/O não bloqueante
  nas chamadas externas sem reescrever a persistência.
- **Migration aplicada é imutável.** O Flyway guarda o checksum do arquivo inteiro; corrigir é
  sempre com migration nova. A esteira monta o schema do zero a cada push e sobe a aplicação
  com `ddl-auto=validate` contra ele.
- **O dono é filtro, não checagem posterior.** Toda consulta leva o id do usuário na cláusula;
  responder 403 para o recurso de outra pessoa confirmaria que aquele id existe.
- **Log não carrega e-mail.** O identificador nas linhas de log é o id do usuário: um UUID não
  carrega quebra de linha nem dado pessoal.
- **Erro sai como `ProblemDetail`**, com mensagem que diz o que fazer.

## Stack

| Camada | O que é usado |
| --- | --- |
| Linguagem | Java 17 (LTS) |
| Framework | Spring Boot 3.4, WebFlux (Netty) |
| Persistência | Spring Data JPA, PostgreSQL, Flyway |
| Segurança | Spring Security, JWT (jjwt), TOTP para segundo fator |
| Resiliência | Resilience4j (circuit breaker), Bucket4j (limite de requisições) |
| Cache | Caffeine |
| Mensageria | RabbitMQ (categorização assíncrona) |
| Arquivos | Apache POI (XLSX), PDFBox (PDF), Commons CSV |
| IA | Spring AI |
| Documentação | SpringDoc OpenAPI (Swagger UI) |
| Testes | JUnit 5, Mockito, Reactor Test, JaCoCo |

## Como rodar

### Pré-requisitos

- JDK 17
- Maven 3.8+
- Docker, para subir Postgres e RabbitMQ locais (`docker compose up -d`)

### Passos

```bash
git clone https://github.com/n33miaz/economize-api.git
cd economize-api
cp .env.example .env   # preencha os valores; nenhum tem default seguro
mvn clean install      # compila e roda a suíte
mvn spring-boot:run
```

A API sobe em `http://localhost:8080`.

> **Atenção ao `.env`.** Ele vira _system property_ e **vence** variável de ambiente. Para
> apontar o banco para outro lugar sem editar o arquivo, passe por argumento de linha de
> comando — é o único caminho que ganha do `.env`.

O `.env.example` lista as chaves esperadas com o que cada uma faz. As integrações opcionais
(Open Finance, IA, mensageria) nascem desligadas: sem credencial, a aplicação sobe e o recurso
correspondente simplesmente não aparece.

### Docker

```bash
docker build -t economize-api .
docker run -p 8080:8080 --env-file .env economize-api
```

## Testes

```bash
mvn test
```

A suíte cobre controllers, services e parsers, e há catraca de cobertura no build — um teste a
menos reprova antes da revisão.

**Nenhum extrato é versionado.** Os casos de parser reproduzem o trecho no próprio arquivo de
teste, escrito à mão a partir do que o layout de cada banco faz. É mais trabalhoso do que
guardar um arquivo, e é a única forma de a suíte não carregar a vida financeira de ninguém.

## Documentação dos endpoints

Com a aplicação rodando:

- **Swagger UI:** `http://localhost:8080/swagger-ui.html`
- **OpenAPI JSON:** `http://localhost:8080/v3/api-docs`

---

## Licença

Projeto desenvolvido para fins acadêmicos e de portfólio.

**Desenvolvedor:** [Neemias Cormino Manso](https://www.linkedin.com/in/neemiasmanso/)
