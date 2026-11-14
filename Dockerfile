FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY whitekey-api/pom.xml whitekey-api/pom.xml
COPY whitekey-server/pom.xml whitekey-server/pom.xml
COPY whitekey-cli/pom.xml whitekey-cli/pom.xml

RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY whitekey-api/src whitekey-api/src
COPY whitekey-server/src whitekey-server/src
COPY whitekey-cli/src whitekey-cli/src

RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app

COPY --from=build /src/whitekey-server/target/whitekey-server-*.jar /app/whitekey-server.jar
COPY --from=build /src/whitekey-cli/target/whitekey-cli-*.jar /app/whitekey-cli.jar

RUN printf '#!/bin/sh\nexec java --enable-native-access=ALL-UNNAMED -jar /app/whitekey-cli.jar "$@"\n' > /usr/local/bin/whitekey \
    && chmod +x /usr/local/bin/whitekey

ENV PROXY_ADDRESS=0.0.0.0

EXPOSE 9999

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/whitekey-server.jar"]
