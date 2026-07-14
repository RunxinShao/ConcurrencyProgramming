Coding Practices for Concurrency Programming and Multithreading

## Lab 1 Performance Test Summary

**Hardware:** Apple M5 Max (top-spec) — 18-core CPU comprising **6 Super Cores + 12 Performance Cores**. No hyper-threading, so each core runs exactly one thread at a time.

> **Note:** M5 Max uses Apple's newer naming convention — Super Cores are the fastest tier, and the 12 Performance Cores are a new mid-tier optimized for efficient multithreaded work, distinct from the classic Efficiency Cores found in M1–M4 generations. The heterogeneous speed gap between the two tiers still applies.

**Workload:** Parallel mean of 15,000,000 `BigInteger` values, split into N equal slices, one thread per slice. Each configuration was run 10 times and averaged.

### Results

| Threads / Slices | Avg time | Speedup (vs 1) | Efficiency | Core placement |
|---|---|---|---|---|
| 1  | 150 ms | 1.00× | 100% | 1 Super Core |
| 2  | 79 ms  | 1.90× | 95%  | 2 Super Cores |
| 4  | 38 ms  | 3.95× | 99%  | 4 Super Cores |
| 8  | 29 ms  | 5.17× | 65%  | 6 Super + 2 Performance |
| 16 | 21 ms  | 7.14× | 45%  | 6 Super + 10 Performance |
| 32 | 20 ms  | 7.50× | 23%  | oversubscribed (> 18 cores) |

### Analysis

**1. Near-linear scaling (1 → 4 threads).** The task is embarrassingly parallel — slices are summed independently with no shared state, no locks, no contention. Up to 4 threads, everything fits inside the 6 Super Cores, giving ~99% efficiency.

**2. Knee at 8 threads — the 6-Super-Core limit.** Efficiency drops from 99% to 65% at 8 threads. This is exactly where thread count exceeds the 6 Super Cores: the 2 extra threads spill onto the slower Performance Cores. Since the program `join()`s all threads, total time is bounded by the *slowest* thread — so the Performance-Core threads become the bottleneck. (A back-of-envelope estimate, `150/8 × ~1.5 Super/Performance speed ratio ≈ 28 ms`, matches the measured 29 ms.)

**3. Diminishing returns (8 → 16).** With 16 threads (6 on Super Cores, 10 on Performance Cores), additional gains come from *more* cores, not *faster* cores. Most work now runs on the slower tier, so efficiency keeps falling.

**4. Plateau at 32 (oversubscription).** Beyond the 18 physical cores, extra threads don't add parallelism — cores just time-slice between threads, adding context-switch overhead. 16 → 32 barely moves (21 → 20 ms).

**5. Amdahl's Law ceiling (~7.5×).** The speedup flattens around 7.5× regardless of thread count. The serial bottleneck is `getSlicedData`, which single-threadedly copies all 15M elements every run (~13% of the 1-thread time). Amdahl's Law predicts a max speedup of `1 / 0.13 ≈ 7.7×`, matching the observed plateau almost exactly.

### Takeaway

On this M5 Max, the sweet spot is **~4–6 threads**, aligned with the 6 Super Cores. Past that, the heterogeneous Super/Performance-core architecture plus a `join`-on-slowest design causes sharp efficiency loss, and the serial slicing step caps the overall speedup at ~7.5× — a core-count-independent limit set by Amdahl's Law. To push further, eliminate the serial copy (use offset/length index ranges so threads read the original array directly).
