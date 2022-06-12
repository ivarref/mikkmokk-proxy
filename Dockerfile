FROM clojure:openjdk-17-tools-deps-1.11.1.1105-buster as builder
WORKDIR /src
COPY deps.edn .
COPY build.edn .
RUN clojure -P && clojure -P -T:build
COPY src/ src/
COPY .git/ .git/
COPY resources/ resources/
RUN clojure -T:build uberjar

FROM eclipse-temurin:17.0.3_7-jdk-focal
COPY --from=builder /src/target/mikkmokk-proxy-standalone.jar /mikkmokk-proxy-standalone.jar
ENTRYPOINT "java" $JAVA_OPTS "-jar" "/mikkmokk-proxy-standalone.jar"
