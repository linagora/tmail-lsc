FROM eclipse-temurin:20-jre-jammy

ADD /lsc-2.1.6 /opt/lsc
RUN mkdir /opt/lsc/conf
RUN chmod a+x /opt/lsc/*

WORKDIR /opt/lsc/bin/
ENV JAVA_OPTS="-DLSC.PLUGINS.PACKAGEPATH=org.lsc.plugins.connectors.james.generated"
ENV CONF_DIR=/opt/lsc/conf/

CMD ["/bin/sh"]