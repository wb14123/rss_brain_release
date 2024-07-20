FROM openjdk:17-slim

ENV JAVA_PARAMS=""

RUN apt update -y && apt install -y wget
RUN cd /opt && \
	wget -c 'https://download.ej-technologies.com/jprofiler/jprofiler_agent_linux-x86_14_0.tar.gz' && \
	tar -xf jprofiler_agent_linux-x86_14_0.tar.gz

WORKDIR '/app'
COPY target/scala-2.13/rss_brain-assembly-0.1.jar .
