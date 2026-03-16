# ── Stage 1: build the plugin JAR ────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build
COPY pom.xml .
# Download dependencies first (better layer caching)
RUN mvn dependency:go-offline -q
COPY src/ src/
RUN mvn package -DskipTests -q

# ── Stage 2: Neo4j with the plugin dropped in ────────────────────────────────
FROM neo4j:5.20.0

# Copy the plugin JAR (the -plugin classifier jar produced by maven-shade-plugin
# contains only our classes; Neo4j's own classes are already on the classpath).
COPY --from=builder /build/target/neo4j-read-only-plugin-*-plugin.jar \
     /var/lib/neo4j/plugins/neo4j-read-only-plugin.jar
