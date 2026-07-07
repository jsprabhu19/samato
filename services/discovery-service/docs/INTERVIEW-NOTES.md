# Discovery Service — Interview Notes

## What it does (1 line)
Service registry. Every service registers here on startup; everyone else
looks up peers here. The API gateway uses it to route by service name.

## Why this service exists
In a microservice system, instances are **ephemeral** — they come and go
(autoscaling, deploys, crashes). You can't hard-code `http://order-2:8080`
because `order-2` may not exist tomorrow. The registry is the source of
truth for **"who is alive right now, and where?"**.

## Patterns demonstrated
| Pattern | Where | Why it matters |
|---------|-------|----------------|
| **Service Registry** | `EnableEurekaServer` | Standard pattern for dynamic discovery |
| **Self-preservation** | `enable-self-preservation: false` (dev only!) | Prevents mass-eviction during a network blip |
| **Health probes** | `actuator.health.probes` | Eureka + K8s use these to gate traffic |
| **Graceful shutdown** | `server.shutdown: graceful` | Lets Eureka get the deregistration notice |

## Interview Q&A

**Q: Eureka vs Consul vs Zookeeper?**
A:
- **Eureka** — simple, battle-tested at Netflix, AP (eventually consistent).
  Good fit for "I just want it to work" in Spring shops.
- **Consul** — feature-rich (KV, DNS, health checks, ACLs). CP-flavored.
  More operations, more power.
- **Zookeeper** — CP, used by Kafka/Hadoop. Heavy operational burden.
  Rarely chosen greenfield today.
- **etcd** — CP, used by K8s. Common in K8s-native stacks.
- **Kubernetes-native (DNS + ClusterIP)** — if you're on K8s, the platform
  *is* your registry. You don't need Eureka.

**Q: What if Eureka itself dies?**
A: Eureka clients **cache the registry**. If Eureka is unreachable, the
last-known-good list is used. Services keep functioning for a while. That's
AP behavior — you stay available, you may send traffic to dead peers, the
load balancer / circuit breaker will detect and route around.

**Q: How do you do health checks?**
A: Two layers:
1. **Eureka heartbeat** — every 30s. If a service misses 3 in a row, Eureka evicts.
2. **Health endpoint** — `/actuator/health/liveness` (am I alive?) and
   `/readiness` (am I ready to take traffic?). The load balancer reads
   these to remove the instance from the pool if e.g. its DB is down.

**Q: Client-side vs server-side load balancing?**
A:
- **Client-side** (Eureka + Spring Cloud LoadBalancer): the **caller** picks an
  instance. Pros: no extra hop. Cons: each client must do discovery.
- **Server-side** (a hardware / Envoy / Istio LB): caller hits a single
  endpoint, the LB picks. Pros: simpler clients, central policy. Cons: extra hop, central point of failure.

## Trade-offs we made
- **Eureka over Consul** for interview familiarity — most Java shops know it.
- **Self-preservation off** in dev so a crashed service actually disappears
  from the dashboard. In prod this would be on (Netflix's default).
- **No replica Eureka servers** in dev. In prod you'd run 2–3 for HA,
  with peer-to-peer replication. Out of scope here.

## Follow-ups interviewers ask
- "How do you secure the registry?" → Basic auth, mTLS, or network policy. Don't expose Eureka publicly.
- "What about service-mesh sidecars?" → They replace the need for a registry (Istio reads K8s endpoints).
- "Can services call each other without discovery?" → Yes, via hard-coded URLs — but you lose dynamic scaling and self-healing.
