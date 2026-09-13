# syntax=docker/dockerfile:1

FROM postgres:17.11-alpine3.24@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73

COPY --chmod=0755 initdb/ /docker-entrypoint-initdb.d/
