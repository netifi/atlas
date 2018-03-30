FROM openjdk:8-jre-alpine

MAINTAINER Netifi Proteus Team

RUN apk --update add bash unzip

# Clustering and RSocket Ports
EXPOSE 7101

RUN mkdir -p /opt/netifi/atlas

COPY target/standalone.jar /opt/netifi/atlas/lib/standalone.jar
COPY start-atlas /opt/netifi/atlas/bin/start-atlas
RUN chmod +x /opt/netifi/atlas/bin/start-atlas

WORKDIR /opt/netifi/atlas

CMD ["./bin/start-atlas"]
