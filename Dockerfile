FROM openjdk:8-jre-alpine

MAINTAINER Netifi Proteus Team

ARG ROUTER_VERSION

RUN apk --update add bash unzip

# Clustering and RSocket Ports
EXPOSE 7101

RUN mkdir -p /opt/netifi/atlas

COPY target/standalone.jar /opt/netifi/atlas/standalone.jar

WORKDIR /opt/netifi/atlas

CMD ["java", "-jar", "$ATLAS_SERVER_OPTS", "standalone.jar"]
