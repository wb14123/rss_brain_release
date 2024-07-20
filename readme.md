
RSS Brain is a modern RSS Reader. Check [the official website](https://www.rssbrain.com/) for more details.

## Requirements

* Java 11 or above
* Scala 2.13 or above

## Build

```
sbt clean generateGRPCCode
sbt compile
sbt assembly
```

## Download and Generate RSSHub rules

```
./bin/download-rsshub-rules.sh
sbt compile convertRsshubRules
```

## Test

Run unit test with coverage:

```
sbt test coverageReport
```

Check dependency vulnerabilities:

```
sbt dependencyCheck
```

TODO: There are some false positives needs to be excluded. See [doc](https://github.com/albuch/sbt-dependency-check).


## Run

Start GRPC server:

```
java -Xmx2G -cp target/scala-2.13/rss_brain-assembly-0.1.jar me.binwang.rss.cmd.GRPCAndHttpServer --grpc --http --frontend
```

Start fetch server:

```
java -Xmx1G -cp target/scala-2.13/rss_brain-assembly-0.1.jar me.binwang.rss.cmd.FetchServer
```
