# Build the static daemon, then ship it on a minimal base. No CGO: a single static
# binary that only needs CAP_NET_RAW (raw ICMP socket) and, to silence the kernel's own
# echo answers, CAP_NET_ADMIN — see docker-compose.yml and the README.
FROM golang:1.24 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags "-s -w" -o /titan-icmp-gw .

FROM gcr.io/distroless/static-debian12
COPY --from=build /titan-icmp-gw /titan-icmp-gw
# Flags are supplied by docker-compose.yml (or on the command line).
ENTRYPOINT ["/titan-icmp-gw"]
