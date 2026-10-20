package com.riskwarning.processing.service.scoring.fact;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorFactExecutorTest {
    @Test void sharedConcurrencyBoundAppliesAcrossRunsAndAllResultsFinish() throws Exception {
        BehaviorFactProperties settings = new BehaviorFactProperties(); settings.setFactConcurrency(3);
        BehaviorFactExecutor executor = new BehaviorFactExecutor(settings); ExecutorService callers = Executors.newFixedThreadPool(2);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), done = new AtomicInteger();
        Runnable job = () -> { int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max);
            try { Thread.sleep(25); done.incrementAndGet(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { active.decrementAndGet(); } };
        try {
            Future<?> first = callers.submit(() -> executor.execute(Collections.nCopies(12, job)));
            Future<?> second = callers.submit(() -> executor.execute(Collections.nCopies(12, job)));
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertEquals(24, done.get()); assertEquals(3, peak.get()); assertEquals(0, active.get());
        } finally { callers.shutdownNow(); executor.destroy(); }
    }

    @Test void aFailureIsReportedOnlyAfterSubmittedTasksHaveExited() {
        BehaviorFactExecutor executor = new BehaviorFactExecutor(new BehaviorFactProperties()); AtomicInteger done = new AtomicInteger();
        try {
            List<Runnable> jobs = new ArrayList<>(); jobs.add(() -> { throw new IllegalStateException("模型不可用"); });
            jobs.addAll(IntStream.range(0, 5).mapToObj(i -> (Runnable)() -> done.incrementAndGet()).collect(Collectors.toList()));
            assertThrows(IllegalStateException.class, () -> executor.execute(jobs)); assertEquals(5, done.get());
        } finally { executor.destroy(); }
    }
}
