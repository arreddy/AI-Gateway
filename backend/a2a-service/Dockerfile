FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /build

COPY pom.xml ./pom.xml
COPY gateway-service/pom.xml        ./gateway-service/pom.xml
COPY auth-service/pom.xml           ./auth-service/pom.xml
COPY routing-engine/pom.xml         ./routing-engine/pom.xml
COPY governance-engine/pom.xml      ./governance-engine/pom.xml
COPY observability-service/pom.xml  ./observability-service/pom.xml
COPY a2a-service/pom.xml            ./a2a-service/pom.xml
COPY mcp-service/pom.xml            ./mcp-service/pom.xml

COPY a2a-service/src ./a2a-service/src

RUN mvn clean package -pl a2a-service -am -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl
COPY --from=builder /build/a2a-service/target/*.jar app.jar
HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
  CMD curl -f http://localhost:8082/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
