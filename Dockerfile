FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
# As dependências vêm numa camada só delas: enquanto o pom não mudar, o deploy
# reaproveita o cache em vez de baixar o repositório inteiro a cada push
COPY pom.xml .
# o "|| true" existe porque go-offline falha em alguns plugins sem quebrar nada:
# o que faltar é baixado no package abaixo, então isto é otimização, não requisito
RUN mvn -B dependency:go-offline || true
COPY src ./src
# Os testes rodam na CI; aqui eles só somariam minutos ao deploy
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

ENV JAVA_OPTS="-Xms256m -Xmx400m"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
