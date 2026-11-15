FROM node:22-bookworm-slim AS frontend
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend
WORKDIR /app
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/ gradle/
COPY src/ src/
COPY --from=frontend /app/dist/ frontend/dist/
RUN ./gradlew --no-daemon test bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /app/build/libs/teamsheet.jar ./teamsheet.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/teamsheet.jar"]
