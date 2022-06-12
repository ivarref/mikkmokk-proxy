FROM clojure:openjdk-17-tools-deps-1.11.1.1105-buster as builder
WORKDIR /src
COPY deps.edn .
COPY build.edn .
RUN clojure -P && clojure -P -T:build
COPY src/ src/
COPY resources/ resources/
RUN clojure -T:build uberjar
