# ==========================================
# ESTÁGIO 1: Extração das Camadas (Builder)
# ==========================================
FROM docker.io/eclipse-temurin:21-jre-alpine AS builder
WORKDIR /build

# Copia o JAR gerado pelo Maven/Gradle
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar

# Extrai o JAR nas suas camadas constituintes usando a ferramenta nativa do Spring Boot
RUN java -Djarmode=layertools -jar application.jar extract

# ==========================================
# ESTÁGIO 2: Imagem Final Otimizada
# ==========================================
FROM docker.io/eclipse-temurin:21-jre-alpine
WORKDIR /app

# Cria um usuário não-root por questões de segurança (Opcional, mas muito bem visto)
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copia as camadas do estágio anterior na ordem otimizada para cache do Docker.
# Camadas que mudam menos (dependências) vêm primeiro.
COPY --from=builder /build/dependencies/ ./
COPY --from=builder /build/spring-boot-loader/ ./
COPY --from=builder /build/snapshot-dependencies/ ./
COPY --from=builder /build/application/ ./

# Inicia a aplicação usando o launcher otimizado do Spring Boot em vez do "java -jar"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]