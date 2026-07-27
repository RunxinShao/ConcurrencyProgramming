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

## Lab 3 Producer–Consumer — Throughput Experiments

![Producer–Consumer throughput experiments](viz/producer-consumer.png)

> The four charts above are rendered by [`viz/producer-consumer.html`](viz/producer-consumer.html) — a self-contained page with hover tooltips, a light/dark toggle, and a data-table view.

**Hardware:** same 18-core machine (6 Super Cores + 12 Performance Cores), JDK 26.

**System.** Producers generate random legal arithmetic operations (operands in −100…+100; division guards against a zero divisor) and try to `add()` them to a shared, *sliced* buffer; consumers `take()` and evaluate them, discarding the result. The buffer is one fixed-size array split into `numSlices` slices, **each slice guarded by its own binary semaphore**. `add()`/`take()` walk the slices, `tryAcquire()` a slice's semaphore, scan that slice for an empty/occupied slot, and always `release()` in a `finally`. Both are **non-blocking**: if no semaphore is won or the slice is full/empty they return failure, and the caller discards the item (producer) or retries (consumer) after a short sleep. The only metric is **throughput = total computations consumed per second**.

**Method.** A harness builds the buffer + P producers + C consumers with **P and C controlled independently** (the deliverable `main` happens to tie both counts to `numSlices`; the harness decouples them so each knob can be varied in isolation). It starts all threads, runs for a fixed window, sets the `running` flags false, `join()`s, and sums every consumer's `consumed`. Each configuration is **3 × 3 s runs averaged, one warm-up discarded**. (The assignment specifies a 30 s window; shorter windows are used here only to keep the ~30-config sweep tractable — a per-second rate is duration-independent.)

**The mechanism that governs everything.** A producer sleeps **1 ms after a successful add, 5 ms after a failure**; a consumer **never sleeps after a successful take**, only 5 ms when it finds the buffer empty. So this is a **blocking / sleep-bound** system, and — as every table below shows — throughput is set almost entirely by how fast producers are throttled by their 1 ms sleep: **≈ 665 successful ops/sec per producer** on this machine.

### A. Buffer size (slices = 4, P = C = 4)

| Buffer size | Slots per slice | Throughput (ops/s) |
|------------:|----------------:|-------------------:|
| 4    | 1   | 1382 |
| 8    | 2   | 2431 |
| 16   | 4   | 3187 |
| 40   | 10  | 3201 |
| 400  | 100 | 2749 |

An **inverted-U**. When the buffer is tiny (1–2 slots per slice) it is constantly full or empty, so threads keep hitting the **failure path and its 5 ms sleep** → starvation drags throughput to 1382. More slots remove those stalls until throughput hits the 4-producer ceiling (≈ 4 × per-producer rate) and **plateaus around 16–40**. At 400 it dips again: `add()`/`take()` scan the *entire slice* while holding the semaphore, so a 100-slot slice means up to 100 slot-checks per operation — pure overhead once the buffer is already big enough. Sweet spot: **just large enough that slices rarely fill**, no larger.

### B. Number of slices / semaphores (buffer = 48, P = C = 8)

| numSlices | Throughput (ops/s) |
|----------:|-------------------:|
| 1  | 5034 |
| 2  | 5403 |
| 4  | 5429 |
| 8  | 5436 |
| 16 | 5416 |
| 48 | 5422 |

**Essentially flat.** More semaphores allow more genuinely-concurrent buffer access, but since producers self-throttle at 1 ms the buffer is *never* the bottleneck — so extra slices buy almost nothing. Only `numSlices = 1` (a single global semaphore that fully serializes all access) is measurably lower (5034 vs ~5420), and even that penalty is tiny because contention isn't the limiter here. **Lesson:** adding concurrency to a shared structure only helps if that structure is actually the bottleneck; here it isn't.

### C. Producer : consumer ratio (buffer = 48, slices = 8)

Varying **producers** (consumers fixed at 8):

| Producers | Throughput (ops/s) |
|----------:|-------------------:|
| 1  | 670   |
| 2  | 1346  |
| 4  | 2702  |
| 8  | 5427  |
| 16 | 10828 |

Varying **consumers** (producers fixed at 8):

| Consumers | Throughput (ops/s) |
|----------:|-------------------:|
| 1  | 5432 |
| 2  | 5419 |
| 4  | 5420 |
| 8  | 5427 |
| 16 | 5432 |

Two opposite behaviours, and this is the heart of the "conveyor belt" balance from the brief:

- **Producers are the throughput lever** — dead linear, throughput ≈ `producers × 665/s`.
- **Consumers barely matter** — dropping from 16 → 1 consumer against 8 producers leaves throughput unchanged (~5420). A **single** consumer keeps up with 8 producers, because a consumer never sleeps on success and is far faster than the sleep-throttled producers. Under these sleep settings the belt is **producer-limited**; extra consumers just find the buffer empty, sleep, and add nothing.

### D. Scaling balanced producer + consumer count (buffer = 64, slices = 8)

| Producers = Consumers | Threads total | Throughput (ops/s) |
|----------------------:|--------------:|-------------------:|
| 1  | 2   | 668   |
| 2  | 4   | 1343  |
| 4  | 8   | 2699  |
| 8  | 16  | 5421  |
| 16 | 32  | 10873 |
| 32 | 64  | 21686 |
| 64 | 128 | 42547 |

**Perfect linear doubling all the way to 128 threads** — throughput ∝ producer count (≈ 665 × n), with no knee at the 18-core limit. This is the same lesson as Lab 2's `Thread.sleep` experiment: the system is **blocking/sleep-bound**, threads spend almost all their time asleep holding no CPU, so hundreds of threads oversubscribe the 18 cores happily and keep paying off. Contrast the *compute-bound* matrix experiments, which saturated at ~18 threads.

### Summary

| Knob | Effect on throughput |
|---|---|
| **Producer count** | The lever. Throughput ≈ `producers × ~665/s`, linear well past the core count. |
| **Consumer count** | Nearly irrelevant under these sleep settings — even 1 consumer keeps up with 8+ producers. |
| **Buffer size** | Inverted-U: too small → starvation via the 5 ms failure sleep; too large → per-op slice-scan overhead. |
| **Slices / semaphores** | Nearly flat — contention isn't the bottleneck, so more semaphores don't help (only `=1` is slightly worse). |

**Cross-cutting conclusion.** Because a producer sleeps on every cycle and a consumer only sleeps when starved, this system is **blocking-bound and producer-throttled**: throughput is governed by *producer count × per-producer rate*, scales linearly with threads far beyond 18 cores, and is almost insensitive to slice count and consumer count. To turn it into a *consumer*- or *contention*-bound system (where slices and consumer count would start to matter), one would shrink the producer sleep or make evaluation expensive — mirroring the compute-vs-blocking divide seen in Lab 2.

> Note: as in Labs 1–2 these are demonstrative measurements (preemptive scheduler, JIT/GC noise, `Thread.sleep` granularity), so absolute ops/sec vary run to run; the **trends** — linear scaling in producer count, the inverted-U in buffer size, and the near-irrelevance of slice/consumer count — are the reproducible results.
