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

# Mesmos valores do render.yaml (que sobrescreve esta linha em producao) e pelo
# mesmo motivo: o teto tem de caber no CONTAINER, contando metaspace, buffers
# diretos e pilhas de thread — nao so no heap. Ver o comentario longo la.
# Medido em producao em 16/09/2026: o metaspace vivia a 118 MB de um teto de
# 128 e a JVM saiu com status 3 (ExitOnOutOfMemoryError). Tirou-se do heap,
# que sobrava, para dar ao metaspace, que nao tinha folga. Racional completo
# no render.yaml, junto das medidas.
ENV JAVA_OPTS="-Xmx192m -XX:MaxMetaspaceSize=176m -XX:MaxDirectMemorySize=32m -XX:ReservedCodeCacheSize=40m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
