FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /build

# All module POMs — parent validates every <module> directory exists
COPY pom.xml ./pom.xml
COPY gateway-service/pom.xml        ./gateway-service/pom.xml
COPY auth-service/pom.xml           ./auth-service/pom.xml
COPY routing-engine/pom.xml         ./routing-engine/pom.xml
COPY governance-engine/pom.xml      ./governance-engine/pom.xml
COPY observability-service/pom.xml  ./observability-service/pom.xml

# Only this module's source
COPY gateway-service/src ./gateway-service/src

RUN mvn clean package -pl gateway-service -am -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl
COPY --from=builder /build/gateway-service/target/*.jar app.jar
HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
