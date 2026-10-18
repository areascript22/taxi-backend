package com.areascript.taxiapp.ride.infra;

import com.areascript.taxiapp.ride.domain.RideTimers;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

// Temporizadores en memoria del proceso (ver RideTimers para el límite que
// eso implica).
@Component
public class SpringRideTimers implements RideTimers {

    private final TaskScheduler taskScheduler;
    private final Clock clock;

    public SpringRideTimers(TaskScheduler taskScheduler, Clock clock) {
        this.taskScheduler = taskScheduler;
        this.clock = clock;
    }

    @Override
    public void expireAfter(Duration delay, Runnable task) {
        taskScheduler.schedule(task, clock.instant().plus(delay));
    }

    @Override
    public void cleanupAfter(Duration delay, Runnable task) {
        CompletableFuture.runAsync(task, CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS));
    }
}
