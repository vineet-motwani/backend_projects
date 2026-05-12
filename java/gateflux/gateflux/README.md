# GateFlux

GateFlux is a modern, reactive API Gateway built using **Spring Boot** and **Spring Cloud Gateway (WebFlux)**. It serves as a secure, rate-limited entry point for backend services, proxying requests while enforcing authentication and usage limits.

## Features

- **Reactive Architecture**: Built on Spring WebFlux and Project Reactor for non-blocking, high-performance request routing.
- **OAuth2 JWT Authentication**: Secures endpoints using JWT tokens validated against an Auth0 issuer.
- **Dynamic Routing**: Configured via `application.yaml` to route and rewrite request paths (e.g., routing `/api/get/**` to `https://httpbin.org`).
- **Distributed Rate Limiting**: Uses **Aerospike** (a high-performance NoSQL database) to track request counts per IP address, limiting users to 5 requests per minute.

## Prerequisites

- **Java Development Kit (JDK)**: Version 21 or higher.
- **Maven**: Used for dependency management and building the project (wrapper provided).
- **Docker or Podman**: To run the local Aerospike database container.

## Getting Started

### 1. Start the Aerospike Database
The gateway requires Aerospike to keep track of rate limits. Start a local instance using Docker or Podman:
```bash
podman run -d --name aerospike -p 3000:3000 -p 3001:3001 -p 3002:3002 aerospike/aerospike-server:latest
```

### 2. Build and Run the Application
Use the included Maven wrapper to start the Spring Boot application:
```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.main.web-application-type=reactive"
```
The gateway will start on `http://localhost:8080`.

### 3. Test the API
To access protected routes, you need a valid Auth0 JWT token with the `read:users` permission.

```bash
curl -i -H "Authorization: Bearer <YOUR_JWT_TOKEN>" http://localhost:8080/api/get
```
*Note: The gateway rewrites `/api/get` to `/get` and forwards it to `httpbin.org`.*

## Project Structure

- `src/main/java/.../GatefluxApplication.java`: The main application entry point.
- `src/main/java/.../SecurityConfig.java`: Configures JWT authentication and route permissions.
- `src/main/java/.../RateLimiterFilter.java`: Custom global filter that connects to Aerospike to enforce rate limits.
- `src/main/resources/application.yaml`: Holds configuration for the gateway routes, security issuer, and database connections.
