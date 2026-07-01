# =========================
# BUILD
# =========================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

RUN chmod +x ./mvnw
RUN ./mvnw dependency:go-offline -B

COPY src ./src
RUN ./mvnw clean package -DskipTests


# =========================
# RUNTIME
# =========================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S javauser && adduser -S javauser -G javauser

# entrypoint de controle de start
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

# app
COPY --from=builder /build/target/*.jar app.jar

USER javauser:javauser

EXPOSE 8080

ENTRYPOINT ["/docker-entrypoint.sh"]

CMD ["java", "-jar", "app.jar"]