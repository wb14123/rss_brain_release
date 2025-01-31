
RSS Brain is a modern RSS Reader. Check [the official website](https://www.rssbrain.com/) for more details.

## Requirements

* Java 11 or above
* Scala 2.13 or above

## Build

### Build Custom Doobie Version 

This version uses a custom branch of doobie to resolve [this issue](https://github.com/typelevel/doobie/issues/2132).

Build the [this branch](https://github.com/wb14123/doobie/tree/stream-leak-patch) locally with:

```
git clone https://github.com/wb14123/doobie
git checkout stream-leak-patch
sbt +publishLocal
```

Then find the built version at `~/.ivy2/local/org.tpolecat/doobie-postgres_2.13` and assign the version to
`doobieVersion` in RSS Brain's `build.sbt`.

### Build RSS Brain

```
sbt clean generateGRPCCode
sbt compile
sbt assembly
```

## Download and Generate RSSHub rules

```
cd ./bin && ./download-rsshub-rules.sh
sbt compile generateRsshubRules
sbt validateRsshubRules
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
