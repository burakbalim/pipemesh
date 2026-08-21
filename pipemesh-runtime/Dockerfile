# The runtime as a process (§26.3).
#
# Two stages so the shipped image carries a JRE and one jar, not a JDK and a
# Maven cache. The build stage is offline against a mounted repository in CI;
# locally it uses whatever ~/.m2 already has.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# The whole source, narrowed by .dockerignore, rather than a list of modules.
# Maven reads every module named in the parent pom whatever it is asked to
# build, so a per-module list is a list that rots the day one is added — which
# is exactly how this failed the first time it was run.
COPY . .
RUN mvn -q -pl pipemesh-runtime -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
# Not root. An image that needs root fails somebody's security review, and by
# then it has already been distributed.
RUN addgroup -S pipemesh && adduser -S -G pipemesh pipemesh
USER pipemesh

WORKDIR /app
# Dependencies first: they change far less often than the application, so this
# layer survives most rebuilds.
COPY --from=build --chown=pipemesh:pipemesh /src/pipemesh-runtime/target/lib lib
COPY --from=build --chown=pipemesh:pipemesh /src/pipemesh-runtime/target/pipemesh-runtime-*.jar app.jar

# Where a mounted configuration directory is expected. Nothing is baked in: the
# workflows a runtime serves are the operator's, not the image's (§31).
ENV PIPEMESH_CONFIG=/etc/pipemesh
ENV PIPEMESH_PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
