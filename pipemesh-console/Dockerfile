# The console as a process: the API and the screens it serves.
#
# Three stages — the frontend is built with Node, the backend with Maven, and
# the image that ships carries a JRE, one jar and the compiled screens.

FROM node:22-alpine AS web
WORKDIR /web
COPY console/web/package.json console/web/package-lock.json ./
RUN npm ci
COPY console/web .
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# The built screens are served by the console itself, so the browser talks to
# one origin and the session cookie needs no cross-site exception.
COPY --from=web /web/dist pipemesh-console/src/main/resources/static
RUN mvn -q -pl pipemesh-console -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S pipemesh && adduser -S -G pipemesh pipemesh
USER pipemesh

WORKDIR /app
COPY --from=build --chown=pipemesh:pipemesh /src/pipemesh-console/target/lib lib
COPY --from=build --chown=pipemesh:pipemesh /src/pipemesh-console/target/pipemesh-console-*.jar app.jar

ENV CONSOLE_PORT=8090
EXPOSE 8090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
