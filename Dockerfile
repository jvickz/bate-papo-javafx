FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY protocol/pom.xml protocol/pom.xml
COPY server/pom.xml server/pom.xml
COPY client/pom.xml client/pom.xml
COPY protocol/src protocol/src
COPY server/src server/src
RUN mvn -B -ntp -pl server -am package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system chat && useradd --system --gid chat chat
COPY --from=build /src/server/target/server-1.0.0.jar /app/server.jar
COPY server/certs/supabase-prod-ca-2021.crt /app/certs/supabase-prod-ca-2021.crt
ENV WAVECOVE_SUPABASE_CA=/app/certs/supabase-prod-ca-2021.crt
USER chat
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-jar", "/app/server.jar"]
