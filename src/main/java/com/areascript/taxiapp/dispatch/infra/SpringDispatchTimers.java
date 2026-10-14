package com.areascript.taxiapp.dispatch.infra;

import com.areascript.taxiapp.dispatch.domain.DispatchTimers;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

// Temporizadores en memoria del proceso, con el mismo TaskScheduler que usa el
// vencimiento de solicitudes (ver DispatchTimers para el límite que implica).
@Component
public class SpringDispatchTimers implements DispatchTimers {

    private final TaskScheduler taskScheduler;
    private final Clock clock;

    public SpringDispatchTimers(TaskScheduler taskScheduler, Clock clock) {
        this.taskScheduler = taskScheduler;
        this.clock = clock;
    }

    @Override
    public void after(Duration delay, Runnable task) {
        taskScheduler.schedule(task, clock.instant().plus(delay));
    }
}
