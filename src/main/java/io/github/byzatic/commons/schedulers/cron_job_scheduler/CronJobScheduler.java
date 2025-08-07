package io.github.byzatic.commons.schedulers.cron_job_scheduler;

import io.github.byzatic.commons.UuidProvider;
import io.github.byzatic.commons.schedulers.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CronJobScheduler implements SchedulerInterface {
    private final static Logger logger = LoggerFactory.getLogger(CronJobScheduler.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<Date, CronTask> tasksMap = new TreeMap<>();

    public CronJobScheduler() {
        initSchedule();
    }

    private void schedule() {
        List<CronTask> tasks = getTasksBefore(new Date());
        for (CronTask task : tasks) {
            StatusResult.Status status = task.getStatusProxy().getStatusResult().getStatus();
            if (status == StatusResult.Status.NEVER_RUN || status == StatusResult.Status.COMPLETE) {
                Thread thread = new Thread(task.getJob());
                threads.add(thread);
                thread.start();
            }


            updateTaskDate(task, task.getNextExecutionDate(new Date()));
        }
    }

    @Override
    public @NotNull String addJob(@NotNull Task task) {
        CronTask localTask = null;
        if (task instanceof CronTask) {
            localTask = (CronTask) task;
        } else {
            throw new IllegalArgumentException("Task should be instance of CronTask");
        }
        String cronExpressionString = localTask.getCronExpressionString();
        String taskId = UuidProvider.generateUuidString();
        TaskStateControlObserverInterface taskStateControlObserver = new TaskTaskStateControlObserver(taskId);
        localTask.observer(taskStateControlObserver);
    }

    @Override
    public void removeJob(@NotNull String taskId) {

    }

    @Override
    public void observer(@NotNull SchedulerObserverInterface schedulerObserver) {

    }

    @Override
    public void cleanup() {

    }

    // Метод для получения всех задач до определенной даты
    private List<CronTask> getTasksBefore(Date date) {
        Map<Date, CronTask> tasksBefore = ((TreeMap<Date, CronTask>) tasksMap).headMap(date);
        return new ArrayList<>(tasksBefore.values());
    }

    // Метод для изменения даты задачи по JobDetail
    private void updateTaskDate(CronTask jobDetail, Date newDate) {
        // Находим текущую дату для указанного JobDetail
        Date currentDate = null;
        for (Map.Entry<Date, CronTask> entry : tasksMap.entrySet()) {
            if (entry.getValue().equals(jobDetail)) {
                currentDate = entry.getKey();
                break;
            }
        }

        // Если задача с таким JobDetail найдена
        if (currentDate != null) {
            // Удаляем старую запись
            tasksMap.remove(currentDate);
            // Добавляем новую запись с обновленной датой
            tasksMap.put(newDate, jobDetail);
        }
    }

    private void initSchedule() {
        Runnable task = this::schedule;
        scheduler.scheduleAtFixedRate(task, 0, 10, TimeUnit.SECONDS);
    }
}
