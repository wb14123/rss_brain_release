#!/bin/bash

# Usage: DB_PASSWORD=mysecretpassword ./start-db.sh

docker stop rss_brain_postgres
docker rm rss_brain_postgres

docker run --name rss_brain_postgres \
	-p 127.0.0.1:5432:5432 \
	-e PGDATA=/var/lib/postgresql/data/pgdata \
	-e POSTGRES_PASSWORD=$DB_PASSWORD \
	-v /vols/rss-brain/db:/var/lib/postgresql/data \
	postgres:12.3
