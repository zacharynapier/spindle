# spindle

A lightweight job scheduler for the JVM, with cron syntax and retries.

Standard cron expressions, retries with backoff, and a clean shutdown, in a few hundred lines
with no dependencies. It's meant for the handful of jobs most services have, like refreshing a
cache every five minutes or pruning old rows overnight, where Quartz is more than you need and
`ScheduledExecutorService` only knows fixed rates.

## Installation

Requires Java 11 or later.

Maven:

```xml
<dependency>
  <groupId>io.github.zacharynapier</groupId>
  <artifactId>spindle</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'io.github.zacharynapier:spindle:0.1.0'
```

## Quickstart

```java
Spindle spindle = Spindle.builder()
        .threads(2)
        .zone(ZoneId.of("America/New_York"))
        .build();

spindle.schedule("*/5 * * * *", () -> cache.refresh());
spindle.schedule("0 9 * * MON-FRI", () -> reports.sendDaily());
```

`schedule` returns a `ScheduledJob` you can inspect or cancel:

```java
ScheduledJob cleanup = spindle.schedule("@daily", () -> db.pruneExpiredSessions());

cleanup.nextFireTime();   // Optional[2020-05-24T00:00-04:00[America/New_York]]
cleanup.cancel();
```

Runs of the same job never overlap. If a run takes longer than the gap until its next fire
time, the missed fires are skipped and the job picks up at the next one.

## Cron syntax

Five fields, separated by spaces:

```
┌───────────── minute        0-59
│ ┌─────────── hour          0-23
│ │ ┌───────── day of month  1-31
│ │ │ ┌─────── month         1-12 or JAN-DEC
│ │ │ │ ┌───── day of week   0-7 or SUN-SAT (0 and 7 are both Sunday)
│ │ │ │ │
* * * * *
```

| Syntax  | Meaning                      | Example           |
|---------|------------------------------|-------------------|
| `*`     | every value                  | `* * * * *`       |
| `a-b`   | range, inclusive             | `0 9-17 * * *`    |
| `a,b,c` | list                         | `0 8,12,18 * * *` |
| `*/n`   | every n-th value             | `*/15 * * * *`    |
| `a-b/n` | every n-th value in a range  | `0-30/10 * * * *` |
| `a/n`   | every n-th value from a      | `5/20 * * * *`    |

Shorthands: `@yearly` (or `@annually`), `@monthly`, `@weekly`, `@daily` (or `@midnight`) and
`@hourly`.

You can also use the parser on its own:

```java
CronExpression cron = CronExpression.parse("30 2 * * SUN");
Optional<ZonedDateTime> next = cron.next(ZonedDateTime.now());
```

## Retries

By default a failed run is reported and not retried. Set a policy for the whole scheduler, or
per job:

```java
Spindle spindle = Spindle.builder()
        .retry(RetryPolicy.exponential(5, Duration.ofSeconds(1))
                .withMaxDelay(Duration.ofMinutes(1))
                .withJitter(0.2))
        .onFailure((job, e) -> log.error("{} failed after retries", job, e))
        .build();

spindle.schedule("0 * * * *", () -> billing.sync(), RetryPolicy.fixed(3, Duration.ofSeconds(10)));
```

`maxAttempts` counts the first run, so `exponential(5, ...)` means one run and up to four
retries. With the policy above the waits between attempts are 1s, 2s, 4s and 8s, each shortened
by a random amount of up to 20%.

| Setting          | Default | Effect                                     |
|------------------|---------|--------------------------------------------|
| `withMultiplier` | `2.0`   | growth factor applied after each failure   |
| `withMaxDelay`   | none    | upper bound on any single delay            |
| `withJitter`     | `0.0`   | fraction (0-1) a delay may be randomly cut |

Retries wait on the scheduler's executor rather than sleeping, so a job in backoff does not hold
a thread. The `onFailure` handler is called once a run has used all of its attempts; without
one, failures are logged through `System.Logger`.

## Shutdown

`Spindle` is `AutoCloseable`. `close()` stops new fires and pending retries, waits for running
jobs to finish, and interrupts any still running after the shutdown timeout (30 seconds unless
set with `shutdownTimeout`).

```java
Runtime.getRuntime().addShutdownHook(new Thread(spindle::close));
```

For finer control, use `shutdown()`, `awaitTermination(Duration)` and `shutdownNow()`, which
behave like their `ExecutorService` counterparts.

## License

MIT. See [LICENSE](LICENSE).
