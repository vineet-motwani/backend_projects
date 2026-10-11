# GateFlux Performance Benchmarks & Architecture Verification

This document records the load testing methodology, reproduction steps, and benchmark results used to verify the architectural claims of GateFlux.

---

## 1. Resume Claim Verification Matrix

| Resume Claim | Architecture Mechanism | Benchmark / Verification Method |
| :--- | :--- | :--- |
| **10k+ concurrent connections with minimal thread overhead** | Netty event loop on Linux `epoll` using a fixed thread pool (`2 * CPU cores`). Socket descriptors are multiplexed in non-blocking I/O rather than dedicating one OS thread per connection. | k6 ramping VUs with HTTP keep-alive connections; OS thread count monitored via `jcmd <pid> Thread.print \| grep -c nid=` stays constant. |
| **Locally verifying RSA-256 JWTs to cut auth latency ~80%** | In-memory cryptographic signature verification using cached Auth0 JWKS public keys, eliminating outbound network HTTP round-trips (`/oauth/token/introspect`) on every request. | Compared local signature verification (~0.1ms CPU time) against typical Auth0 introspection latency (40ms - 90ms network round-trip). |
| **Strict RBAC enforcement across 100% of proxy routes** | Custom `JwtGrantedAuthoritiesConverter` mapped to `permissions` claim and configured in `SecurityConfig` (`.pathMatchers("/api/**").hasAuthority("read:users")`). | Automated test suite verifying unauthenticated 401s, unauthorized 403s, and authenticated 200s. |
| **Sub-millisecond (<1ms) rate limit evaluation with fail-open** | Aerospike single-record atomic `Operation.add` + `Operation.get` with 60s TTL, offloaded to Project Reactor's `Schedulers.boundedElastic()`. | k6 request duration percentiles; Aerospike server latency log showing `<0.8ms` execution time per atomic hit. |

---

## 2. Benchmark Environment

- **Server Instance**: AWS EC2 `t3.small` Spot Instance (2 vCPU, 2GB RAM, Ubuntu 24.04 LTS).
- **Runtime**: OpenJDK 21 / Netty 4.2 WebFlux.
- **Upstream Service**: Local high-throughput container (`mccutchen/go-httpbin`) to eliminate external network jitter and remote rate-limiting.
- **Load Generator**: `k6` executing from client machine or benchmark runner instance.

---

## 3. How to Run the Benchmarks

### Option A: Local Run (Docker Compose)

1. Start the stack with the high-throughput local mock upstream:
   ```bash
   docker compose up -d --build
   ```

2. Run the k6 load test:
   ```bash
   TARGET_HOST="http://localhost:8080" TOKEN="<YOUR_AUTH0_TOKEN>" k6 run infra/loadtest/k6-benchmark.js
   ```

### Option B: Cloud Run (AWS Spot Instance via Terraform)

1. Deploy the instance (cost: ~$0.008/hr):
   ```bash
   cd infra/terraform
   terraform init
   terraform apply -auto-approve
   ```

2. Deploy the application to the instance:
   Run the `deploy_command` provided in Terraform outputs.

3. Run k6 against the public IP:
   ```bash
   TARGET_HOST="http://<INSTANCE_PUBLIC_IP>:8080" TOKEN="<YOUR_AUTH0_TOKEN>" k6 run infra/loadtest/k6-benchmark.js
   ```

4. **Tear down infrastructure immediately after testing**:
   ```bash
   cd infra/terraform
   terraform destroy -auto-approve
   ```

---

## 4. Benchmark Results Log

Record benchmark runs here to track latency percentiles across iterations:

### Run 1: Baseline Load Test (50 VUs, Local Docker Compose)
- **Date**: October 2026
- **Target**: `http://localhost:8080/api/get` (proxied to `go-httpbin`)
- **Throughput**: ~480 req/s
- **Latency Percentiles**:
  - `p(50)`: 1.8 ms
  - `p(90)`: 3.4 ms
  - `p(95)`: 4.8 ms
  - `p(99)`: 8.2 ms
- **Memory Overhead**: JVM RSS ~120 MB (Netty buffers + Reactor pipeline).
- **Active Thread Count**: Flat at 8 event-loop worker threads (`2 * 4 CPU cores`).
