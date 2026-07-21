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

## Optimization: Zero-Copy Slicing

Following the takeaway above, the serial slicing step was removed. Instead of copying each slice into a fresh array on the main thread, every worker now receives `(sharedArray, start, length)` and reads its own contiguous range **directly from the original array** — no data is copied.

This is thread-safe: all threads only **read** the shared array (no thread writes it), and their ranges are non-overlapping. A data race requires at least one writer, so read-only sharing needs no locks.

### Results — before vs after

| Threads / Slices | Before (copy) | After (zero-copy) | Speedup vs 1 (after) |
|---|---|---|---|
| 1  | 150 ms | 141 ms | 1.00× |
| 2  | 79 ms  | 63 ms  | 2.24× |
| 4  | 38 ms  | 22 ms  | 6.4× |
| 8  | 29 ms  | 12 ms  | 11.75× |
| 16 | 21 ms  | 9 ms   | 15.7× |
| 32 | 20 ms  | 9 ms   | 15.7× |

### Why it got faster

Two distinct effects combine:

**1. Less total work (absolute times drop across the board).** The ~15M-element copy is gone entirely, so even a single thread improves (150 → 141 ms) despite having no parallelism to gain from — the work itself shrank.

**2. Smaller serial fraction → higher Amdahl ceiling (this is the big one).** Amdahl's Law caps max speedup at `1 / serial_fraction`. Before, the serial copy was ~13% of the 1-thread time, capping speedup at `1 / 0.13 ≈ 7.7×` — which is exactly where the old numbers plateaued. Removing it drives the serial fraction toward ~0, lifting the ceiling toward the physical core count. The measured speedup now reaches **~15.7×** (approaching the 18-core limit) instead of stalling at 7.5×, and the previously-flat 8→16 region regains near-linear gains.

> Note: these are demonstrative measurements, not a rigorous benchmark. The OS scheduler is preemptive (threads can be interrupted or migrated between cores), and JIT warmup / GC pauses add noise, so the near-/super-linear figures past 4 threads carry measurement error. The **trend** — a much higher speedup ceiling once the serial copy is removed — is the robust, reproducible result.

## Lab 2 Matrix Multiplication — Discussion Experiments

**Hardware:** same 18-core machine (6 Super Cores + 12 Performance Cores), JDK 26.

Two parallelization strategies for multiplying `N/2` pairs of matrices held in a shared buffer:

- **Task 1 — static partitioning (lock-free):** the work range is split up front, each worker owns a fixed `[leftIndex, leftIndex + count)` interval, reads the shared buffer, and writes into its own private `res` list. No shared mutable state, so no locks.
- **Task 2 — dynamic work-stealing:** workers pull `SECTION_SIZE`-sized chunks at runtime via a `synchronized getNextSection()` cursor. Self-balancing, but every fetch contends on one lock.

All timings use a JIT warm-up pass and report the steady-state run.

### Task 1 — A. Varying `NUM_OF_WORKERS` (dim = 200, 500 pairs, compute-bound)

| Workers | Time | Speedup |
|--------:|-----:|--------:|
| 1     | 1682 ms | 1.0× |
| 2     | 903 ms  | 1.9× |
| 4     | 471 ms  | 3.6× |
| 8     | 296 ms  | 5.7× |
| 16    | 173 ms  | 9.7× |
| 1000  | 151 ms  | 11.1× |
| 10000 | 344 ms  | 4.9× |

Speedup climbs but stays **sub-linear** (16 threads → 9.7×, not 16×) because of thread scheduling overhead, memory bandwidth limits, and 500 pairs not dividing evenly across workers. It saturates near the physical core count (18). Going to 1000 threads squeezes out a hair more by hiding tail load-imbalance, but **10000 threads slow down to 344 ms** — for compute-bound work, far more threads than cores buys only creation and context-switch overhead.

### Task 1 — B. Varying `DIMENSION` (single thread, testing the "double → 8×" law)

Small dims (amplified to 200,000 pairs to be measurable):

| dim | Time | Ratio |
|----:|-----:|------:|
| 2 | 15 ms | — |
| 4 | 29 ms | 1.9× |
| 8 | 76 ms | 2.6× |

Large dims (2000 pairs each):

| dim | Time | Ratio |
|----:|-----:|------:|
| 32  | 23 ms   | — |
| 64  | 174 ms  | 7.5× |
| 128 | 1596 ms | 9.1× |

Matrix multiply is O(dim³), so doubling the dimension should cost ~8×. But that law is **asymptotic**: at small dims the per-pair fixed costs (allocating the result array, `ArrayList.add`, loop setup — all O(dim²) or constant) dominate, so the ratio is only 1.9–2.6×. Once the arithmetic dominates (32 → 64 → 128), the ratio reaches 7.5× then 9.1× (slightly above 8× as cache locality degrades).

### Task 1 — C. Adding `Thread.sleep(10)` in the loop (dim = 10, 500 pairs, 10 ms per pair)

| Workers | Time |
|--------:|-----:|
| 1   | 6096 ms |
| 2   | 3034 ms |
| 4   | 1517 ms |
| 8   | 757 ms |
| 16  | 391 ms |
| 32  | 196 ms |
| 64  | 102 ms |
| 500 | 22 ms |
| 1000| 25 ms |

This is the key contrast. Serial sleep time ≈ 500 × 10 ms = 5000 ms, and adding threads **halves the time almost perfectly** — and keeps helping *far past* 18 cores (32 → 196, 64 → 102, 500 → 22 ms). `sleep` simulates **blocking / I/O**: a sleeping thread holds no CPU, so oversubscription pays off. This is exactly the dividing line: **compute-bound → threads ≈ cores; blocking-bound → many more threads than cores wins.**

### Task 2 — A. Varying `SECTION_SIZE` (18 workers, 500,000 pairs, dim = 10, compute-bound)

| SECTION_SIZE | Time | ≈ Lock acquisitions |
|-------------:|-----:|--------------------:|
| 1      | 168 ms | 500000 |
| 10     | 65 ms  | 50000 |
| 100    | 59 ms  | 5000 |
| 1000   | 47 ms  | 500 |
| 500000 (whole task) | 294 ms | 1 |

A classic **U-shape** — both extremes are bad:

- **Too small (1):** every single pair grabs the lock → 500k `synchronized` contentions, dragging the time to 168 ms.
- **Too large (whole task):** the first thread claims all 500k pairs; the other 17 immediately get `-1` and idle → **degenerates to serial**, the worst at 294 ms.
- **Middle (~1000) is optimal (47 ms):** lock traffic is negligible and the load still spreads evenly.

### Task 2 — B. Varying `NUM_OF_WORKERS` (dim = 100, 4000 pairs, section = 10)

| Workers | Time |
|--------:|-----:|
| 1     | 1450 ms |
| 2     | 758 ms |
| 4     | 392 ms |
| 8     | 208 ms |
| 16    | 145 ms |
| 1000  | 133 ms |
| 10000 | 335 ms |

Same shape as Task 1: scales to ~16 threads, plateaus at 1000, regresses at 10000 due to thread-creation overhead. Versus Task 1's static split, dynamic scheduling adds **automatic load balancing** (fast threads grab more chunks) at the cost of per-fetch lock overhead.

### Task 2 — C. Adding `Thread.sleep(10)` (dim = 10, 500 pairs, section = 10 → 50 chunks)

| Workers | Time |
|--------:|-----:|
| 1   | 6078 ms |
| 2   | 3017 ms |
| 4   | 1570 ms |
| 8   | 845 ms |
| 16  | 478 ms |
| 32  | 245 ms |
| 64  | 123 ms |
| 500 | 125 ms |

Blocking work again keeps scaling past the core count (32 → 245, 64 → 123 ms). But note **64 and 500 both flatline at ~123 ms**: with `section = 10`, the 500 pairs form only **50 chunks**, so at most 50 threads can ever be busy — extra threads find nothing to steal. In other words, `SECTION_SIZE` also **caps the maximum useful parallelism** at `offset / section`.

### Summary — Task 1 vs Task 2

| Aspect | Task 1 (static) | Task 2 (dynamic) |
|---|---|---|
| Assignment | fixed intervals decided up front | chunks claimed at runtime |
| Locking | none (private `res` per worker) | `synchronized` cursor — contended |
| Load balancing | poor (slow thread drags the `join`) | good (fast threads grab more) |
| Key knob(s) | `NUM_OF_WORKERS` | `NUM_OF_WORKERS` + `SECTION_SIZE` |
| Main lesson | compute → threads ≈ cores; 8× law is asymptotic | too small → lock contention; too large → serial + parallelism cap |

**Cross-cutting conclusions:**

1. **Compute-bound** work is fastest at roughly the physical core count (18 here); more is pure overhead (10000 threads regressed in both tasks).
2. **Blocking / I/O-bound** work (`sleep`) keeps speeding up well beyond the core count, because blocked threads free the CPU.
3. Task 2's `SECTION_SIZE` is the **lock-contention ↔ load-balance / parallelism** trade-off dial: too small explodes lock traffic, too large serializes the work and caps the maximum concurrency at `offset / section`.

> Note: like Lab 1, these are demonstrative measurements (preemptive scheduler, JIT/GC noise), so absolute numbers vary run to run; the **trends** — the compute-vs-blocking divide and the U-shaped `SECTION_SIZE` curve — are the reproducible results.
