# Etapa 1: compilar
# (Se construye en tu PC o en CI, NO en el droplet: Maven consume ~1 GB y en un
# droplet de 2 GB tumbaría a Postgres.)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# Etapa 2: ejecutar
FROM eclipse-temurin:21-jre
WORKDIR /app

# No corre como root. El uid es fijo para poder darle permiso de lectura sobre
# la credencial de Firebase montada desde el host (chown 10001).
RUN groupadd --system --gid 10001 taxiapp \
 && useradd --system --uid 10001 --gid taxiapp --no-create-home --shell /usr/sbin/nologin taxiapp

COPY --from=build /app/target/*.jar app.jar
USER taxiapp

EXPOSE 8080
# Las opciones de la JVM se pasan con JAVA_TOOL_OPTIONS (ver compose.yaml).
ENTRYPOINT ["java", "-jar", "app.jar"]
