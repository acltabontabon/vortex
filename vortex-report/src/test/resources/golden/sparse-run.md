## Vortex performance result — NOT EVALUATED

> Does it work at all?
>
> **Not established.**

**Service:** checkout-service 2.17.0  
**Workload:** average_load  
**Environment:** local (Isolated performance test)  
**Duration:** 10m  
**Ran:** 2026-08-21 10:10 UTC  

### Workload

| | Configured | Achieved |
|---|---:|---:|
| Rate | 20 requests/sec | — |
| Duration | 10m | 10m |
| Requests | ~12000 | 100 |

**Mix:** 70% GET /accounts/{id}, 30% GET /orders/{id}

### Performance

| Metric | Value |
|---|---:|
| Error rate | 0% |
| Requests | 100 |

**Headroom:** not stated. No tested capacity is available: this run never established a compliant level.

### Acceptance criteria

This run had no objectives configured, so it can neither pass nor fail. The measurements below stand on their own.

### Operations

| Operation | Rate | p95 | p99 | Errors |
|---|---:|---:|---:|---:|
| GET /accounts/{id} | — | — | — | no traffic |
| GET /orders/{id} | — | — | — | no traffic |

#### Request data

**GET /accounts/{id}**

- id (path parameter) — fixed: acc-1001

**GET /orders/{id}**

- id (path parameter) — fixed: ord-1

### Findings

- **Warning** — This run had no objectives, so it neither passed nor failed.
- **Warning** — GET /accounts/{id} issued no requests.
- **Warning** — GET /orders/{id} issued no requests.

<details>
<summary>Reproducing this run</summary>

```
vortex run average_load --environment local
```

**Run:** a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6  
**Vortex:** 0.1.0  
**Engine:** k6 v1.3.0  
**Configuration:** e5bdfbeb  

</details>
