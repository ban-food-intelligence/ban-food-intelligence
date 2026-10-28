# ============================================================================
# BAN Food Intelligence - Go REST API (Berke) - multi-stage build
# ============================================================================
FROM golang:1.25-alpine AS build
WORKDIR /src
RUN apk add --no-cache git ca-certificates
COPY go.mod go.sum* ./
COPY . .
# go.sum is resolved at build time so the image is reproducible from go.mod alone
RUN go mod tidy && go mod download
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/api-go .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates wget tzdata && adduser -D -u 10001 app
WORKDIR /app
COPY --from=build /out/api-go /app/api-go
USER app
ENV PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=10 CMD wget -qO- http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["/app/api-go"]
