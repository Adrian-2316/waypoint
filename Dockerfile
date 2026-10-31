# 1. Front end
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build -- --outDir /static

# 2. Quarkus app
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src/ src/
COPY --from=web /static src/main/resources/META-INF/resources
RUN mvn -B -q package -DskipTests

# 3. Runtime, as an unprivileged user
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/quarkus-app/ ./
RUN useradd --uid 10001 --no-create-home waypoint
USER waypoint
EXPOSE 8080
CMD ["java", "-jar", "quarkus-run.jar"]
