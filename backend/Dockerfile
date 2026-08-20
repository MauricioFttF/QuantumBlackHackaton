# ---- Estágio 1: build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
# O bit de execução do wrapper não sobrevive a todo checkout (o repo já o perdeu uma vez), e sem
# ele este RUN falha com exit 126 e a imagem não builda. Barato de garantir aqui.
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src src
RUN ./mvnw package -DskipTests -B

# ---- Estágio 2: runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# O corpus fica fora do jar e é lido por caminho relativo ao diretório de trabalho
# (IngestionService confina o 'path' a ele). Sem esta linha, a ingestão — no boot ou por
# POST /api/ingest — falha com NoSuchFileException e o chat sobe sem nada para responder.
COPY data ./data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
