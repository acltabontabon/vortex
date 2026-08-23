## Vortex performance result — PASS

> Can the service hold 20 requests/sec within its objectives?
>
> **Yes, with every objective met.**

**Service:** checkout-service 2.17.0  
**Workload:** average_load  
**Environment:** local (Isolated performance test)  
**Duration:** 10m  
**Ran:** 2026-08-21 10:10 UTC  

> Dependencies were mocked, so this cannot establish production capacity.

### Workload

| | Configured | Achieved |
|---|---:|---:|
| Rate | 20 requests/sec | 19.8 requests/sec |
| Duration | 10m | 10m |
| Requests | ~12000 | 30852 |

The configured workload was sustained (99% delivered).

**Mix:** 70% GET /accounts/{id}, 30% GET /orders/{id}

### Performance

| Metric | Value |
|---|---:|
| p50 | 70 ms |
| p95 | 281 ms |
| p99 | 449 ms |
| max | 843 ms |
| Error rate | 0.08% |
| Requests | 30852 |

**Headroom:** not stated. No tested capacity is available: this run never established a compliant level.

### Acceptance criteria

| Criterion | Observed | Result |
|---|---:|---|
| p95 latency below 500 ms | 281 ms | PASS |
| p99 latency below 1 s | 449 ms | PASS |
| error rate below 1% | 0.08% | PASS |

### Operations

| Operation | Rate | p95 | p99 | Errors |
|---|---:|---:|---:|---:|
| GET /accounts/{id} | 60 requests/sec | 90 ms | 170 ms | 0.01% |
| GET /orders/{id} | 40 requests/sec | 320 ms | 610 ms | 0.09% |

#### Request data

**GET /accounts/{id}**

- id (path parameter) — fixed: acc-1001

**GET /orders/{id}**

- id (path parameter) — fixed: ord-1

### Over time

`Throughput ▂▃▃▃▃▃▃▃▃▃▃▄▄▄▄▄▄  ▄▅▅▅▅▅▅▅▅▅▅▆▆▆▆▆▆▆▆▆▆` 0–100 requests/sec

`p95        ▂▂▂▂▂▂▂▂▂▂▂▂▂▂▃▃▃  ▃▃▃▃▃▃▃▃▃▃▃▄▄▄▄▄▄▄▄▄▄` 0–1k ms

`Errors     ▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▄▄▄▄▄▄▄▄▄▄` 0–5 %


### From the service under test

| Signal | Peak | Movement | Source |
|---|---:|---|---|
| hikaricp.connections.utilization | 94 % | 31 % → 94 % → 47 % | Service metrics endpoint |
| system.cpu.usage | 81 % | 42 % → 81 % → 55 % | Service metrics endpoint |

### Findings

- **Observation** — hikaricp.connections.utilization reached 94 % during the run.
- p95 latency below 500 ms was met, at 281 ms.
- p99 latency below 1 s was met, at 449 ms.
- error rate below 1% was met, at 0.08%.
- The configured workload of 20 requests/sec was sustained for the whole run.

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
