# Economize! - API (BFF)

Este projeto é um **Backend for Frontend (BFF)** desenvolvido em **Java 17** com **Spring Boot 3**. Ele atua como uma camada de orquestração e segurança para o aplicativo mobile **Economize!**, centralizando regras de negócio, cacheamento de dados e integração com APIs externas de mercado financeiro.

> **Destaque:** O projeto utiliza **Programação Reativa (Spring WebFlux)** para alta performance e **Caffeine Cache** para otimização de requisições.

## 🔗 Integração Mobile

Este backend serve dados exclusivamente para o aplicativo mobile desenvolvido em React Native.

- **Repositório Mobile:** [painel_economico-mobile](https://github.com/n33miaz/painel_economico-mobile)

---

## 🛠 Tecnologias e Arquitetura

A arquitetura foi pensada para ser escalável, resiliente e fácil de manter, utilizando as melhores práticas do ecossistema Spring:

- **Linguagem:** Java 17 (LTS).
- **Framework:** Spring Boot 3.5.
- **Stack Reativa:** Spring WebFlux (Netty) para I/O não bloqueante.
- **Cliente HTTP:** WebClient (Chamadas assíncronas a APIs externas).
- **Cache:** Caffeine (Cache em memória para reduzir latência e economizar cota de APIs externas).
- **Documentação:** SpringDoc OpenApi (Swagger UI).
- **Testes:** JUnit 5, Mockito e Reactor Test (Cobertura de testes unitários e de integração).
- **Build & Deploy:** Maven e Docker.

## 🚀 Funcionalidades da API

### 1. Agregação de Indicadores Financeiros

Consolida dados de múltiplas fontes (moedas, criptoativos e índices de bolsa) em um formato unificado para o frontend.

- **Endpoint:** `/api/indicators/all`

### 2. Dados Históricos

Fornece histórico de variação de preços (High/Low) dos últimos dias para geração de gráficos.

- **Endpoint:** `/api/indicators/historical/{currencyCode}`

### 3. Motor de Conversão de Moedas

Realiza cálculos de conversão monetária no lado do servidor, garantindo que a regra de negócio e a cotação utilizada sejam confiáveis.

- **Endpoint:** `/api/indicators/convert`

### 4. Feed de Notícias

Proxy para a API de notícias globais, protegendo a API Key no servidor (evitando exposição no app mobile) e tratando falhas de fornecedores externos com _fallbacks_.

- **Endpoint:** `/api/news/top-headlines`

---

## ⚙️ Como executar localmente

### Pré-requisitos

- JDK 17 instalado.
- Maven 3.8+.
- Uma chave de API gratuita do [NewsAPI](https://newsapi.org/) (Opcional, o sistema possui fallback).

### Passos

1. Clone o repositório:

   ```bash
   git clone https://github.com/n33miaz/painel_economico-api.git
   cd painel_economico-api
   ```

2. Configuração de Variáveis de Ambiente:
   Crie um arquivo `.env` na raiz do projeto (ou configure nas variáveis do sistema):

   ```properties
   NEWS_API_KEY=sua_chave_aqui
   ```

3. Instale as dependências e execute os testes:

   ```bash
   mvn clean install
   ```

4. Execute a aplicação:
   ```bash
   mvn spring-boot:run
   ```
   A API estará disponível em: `http://localhost:8080`

### 🐳 Executando com Docker

```bash
docker build -t economize-api .
docker run -p 8080:8080 -e NEWS_API_KEY=sua_chave economize-api
```

---

## 🧪 Testes Automatizados

A qualidade do código é garantida através de testes unitários e de integração, cobrindo Controllers e Services.

Para rodar os testes:

```bash
mvn test
```

## 📚 Documentação (Swagger)

Com a aplicação rodando, acesse a documentação interativa dos endpoints:

- **Swagger UI:** `http://localhost:8080/swagger-ui.html`
- **OpenAPI Json:** `http://localhost:8080/v3/api-docs`

---

## 📄 Licença

Este projeto foi desenvolvido para fins acadêmicos e de portfólio.

**Desenvolvedor:** [Neemias Cormino Manso](https://www.linkedin.com/in/neemiasmanso/)
