# RSS Brain

RSS Brain is a modern, self-hosted RSS feed reader with AI-powered features. Check [the official website](https://www.rssbrain.com/) for more details.

## Features

- Subscribe to RSS/Atom feeds, Reddit, and Hacker News
- Organize feeds into folders with customizable layouts (list or grid)
- Full-text search powered by Elasticsearch with semantic vector search
- AI-powered article recommendations using OpenAI
- OPML import/export for migrating from other readers
- Article bookmarking, read tracking, and NSFW filtering
- Audio/video playback progress tracking (YouTube, podcasts)
- Stripe and Apple Pay subscription support
- Responsive web UI with light/dark themes

## Architecture Overview

RSS Brain is a monolithic Scala application with two runtime processes:

- **Main server** (`GRPCAndHttpServer`) — serves the web UI, gRPC API, and HTTP webhooks
- **Fetch server** (`FetchServer`) — background worker that polls RSS feeds and generates article embeddings

### Tech Stack

**Backend:** Scala 2.13, Cats Effect, http4s, Doobie + Quill, elastic4s, Circe, sttp

**Frontend:** Server-side rendered HTML (Scalatags) with [HTMX](https://htmx.org/) for dynamic interactions and [Alpine.js](https://alpinejs.dev/) for client-side reactivity. Styled with [Pico CSS](https://picocss.com/). Bundled with Webpack.

**Data stores:**

| Store         | Purpose                                          |
|---------------|--------------------------------------------------|
| PostgreSQL    | Primary data store (users, feeds, articles, etc) |
| Elasticsearch | Full-text search and vector similarity search    |
| Redis         | Session caching and article deduplication        |

**External services (optional):**

- OpenAI API — article recommendations
- A sentence embedding gRPC service — vector embeddings for semantic search
- Stripe — payment processing
- SendGrid — transactional emails
- Reddit API — Reddit feed integration

### Project Structure

```
src/main/scala/me/binwang/rss/
├── cmd/            # Server entry points
├── dao/            # Data access (sql/, elasticsearch/, redis/, hybrid/)
├── fetch/          # Feed fetching and article embedding workers
├── grpc/           # gRPC API layer
├── llm/            # OpenAI integration
├── mail/           # Email sending (SendGrid)
├── metric/         # Prometheus metrics
├── model/          # Data models
├── ops/            # Operational utilities
├── parser/         # RSS, Atom, Reddit, Hacker News parsers
├── reddit/         # Reddit OAuth and API integration
├── service/        # Business logic
├── sourcefinder/   # Auto-discover feed URLs from web pages
├── time/           # Time utilities
├── util/           # General utilities
├── web/            # HTTP webhook handlers (Stripe, Apple Pay, export)
└── webview/        # Web UI routes and HTML widgets

src/main/resources/
├── application.conf   # All configuration
└── webview/           # Static frontend assets

js/                    # Frontend JavaScript & CSS (Webpack project)
```

`dao/sql/DoobieContext.scala` is a copy of quill-doobie's adapter. It is kept in-tree because quill-doobie has no
Scala 2.13 release that works with doobie 1.0.0-RC7 and later.

## Requirements

- Java 21
- sbt (Scala Build Tool)
- Node.js 20.9+ and npm (for frontend build)
- PostgreSQL
- Elasticsearch 8.x
- Redis

## Build

### Build RSS Brain

```bash
sbt clean generateGRPCCode
sbt compile
sbt assembly
```

The frontend (JS/CSS) is built automatically via Webpack during `sbt compile`.

### Download and Generate RSSHub Rules (Optional)

[RSSHub](https://docs.rsshub.app/) rules enable automatic feed URL discovery for popular websites.

```bash
cd ./bin && ./download-rsshub-rules.sh
sbt compile generateRsshubRules
sbt validateRsshubRules
```

## Configuration

All settings are in `src/main/resources/application.conf`. Key sections:

| Section             | Description                                     |
|---------------------|-------------------------------------------------|
| `db`                | PostgreSQL connection (url, user, password)      |
| `elasticsearch`     | Elasticsearch host, port, index settings         |
| `redis`             | Redis connection URL and key prefix              |
| `grpc`              | gRPC server bind address (default: 8080)         |
| `http`              | HTTP webhook server (default: 8082)              |
| `frontend`          | Web UI server (default: 8084)                    |
| `metrics`           | Prometheus metrics endpoint (default: 8083)      |
| `payment`           | Stripe API keys; set `enabled = false` to disable|
| `reddit`            | Reddit OAuth credentials                         |
| `sendgrid`          | SendGrid API key for emails                      |
| `open-ai`           | OpenAI model for recommendations                 |
| `article-embedding` | Toggle and tune the embedding worker             |
| `ai-server`         | gRPC address of the sentence embedding service   |
| `throttling`        | Per-user and per-IP rate limits                   |
| `import.limit`      | Max folders/sources for free vs paid users        |

## Run

Two processes need to run:

**Main server** (web UI + gRPC API + HTTP webhooks):

```bash
java -Xmx2G -cp target/scala-2.13/rss_brain-assembly-0.1.jar \
  me.binwang.rss.cmd.GRPCAndHttpServer --grpc --http --frontend
```

**Background fetcher** (RSS polling + embedding generation):

```bash
java -Xmx1G -cp target/scala-2.13/rss_brain-assembly-0.1.jar \
  me.binwang.rss.cmd.FetchServer
```

The web UI is available at `http://localhost:8084` by default.

Database tables are created automatically on first startup.

## Docker

Build and deploy with the included script:

```bash
./build-and-deploy.sh
```

This script:
1. Builds the assembly JAR
2. Builds a Docker image based on Eclipse Temurin JRE 21
3. Pushes to `ghcr.io/wb14123/rss_brain:<git-commit>`
4. Updates Kubernetes deployments (`rss-brain-grpc` and `rss-brain-fetcher`)

Set `DEPLOY=false` at the top of the script to skip the Kubernetes deployment step.

## Test

Run unit tests with coverage:

```bash
sbt test coverageReport
```

Check dependency vulnerabilities:

```bash
sbt dependencyCheck
```
