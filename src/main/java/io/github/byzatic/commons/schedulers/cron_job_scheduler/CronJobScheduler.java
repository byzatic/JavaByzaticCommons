package io.github.byzatic.commons.schedulers.cron_job_scheduler;

import io.github.byzatic.commons.schedulers.SchedulerInterface;
import io.github.byzatic.commons.schedulers.cron_job_scheduler.job.CJSJobDetail;
import io.github.byzatic.commons.schedulers.cron_job_scheduler.proxy.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class CronJobScheduler implements SchedulerInterface {
    private final static Logger logger = LoggerFactory.getLogger(CronJobScheduler.class);
    private final Map<Date, CJSJobDetail> tasksMap = new TreeMap<>();

    @Override
    public synchronized void addJob(CJSJobDetail jobDetail) {
        tasksMap.put(jobDetail.getNextExecutionDate(new Date()), jobDetail);
    }

    // Метод для получения всех задач до определенной даты
    private List<CJSJobDetail> getTasksBefore(Date date) {
        Map<Date, CJSJobDetail> tasksBefore = ((TreeMap<Date, CJSJobDetail>) tasksMap).headMap(date);
        return new ArrayList<>(tasksBefore.values());
    }

    // Метод для изменения даты задачи по JobDetail
    private void updateTaskDate(CJSJobDetail jobDetail, Date newDate) {
        // Находим текущую дату для указанного JobDetail
        Date currentDate = null;
        for (Map.Entry<Date, CJSJobDetail> entry : tasksMap.entrySet()) {
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

    @Override
    public void runAllTasks(Boolean isJoinThreads) {
        List<CJSJobDetail> tasks = getTasksBefore(new Date());

        List<Thread> threads = new ArrayList<>();

        // Создаем поток для каждой задачи и запускаем их
        for (CJSJobDetail task : tasks) {
            Status.StatusCode statusCode = task.getStatusProxy().getStatusResult().getStatus();
            if (statusCode == Status.StatusCode.NEVER_RUN || statusCode == Status.StatusCode.COMPLETE) {
                Thread thread = new Thread(task.getJob());
                threads.add(thread);
                thread.start();
            }
            updateTaskDate(task, task.getNextExecutionDate(new Date()));
        }

        if (isJoinThreads) {
            // Ожидаем завершения всех потоков
            for (Thread thread : threads) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    logger.debug("Thread was interrupted: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void checkHealth() throws OperationIncompleteException {
        for (Map.Entry<Date, CJSJobDetail> jobDetailEntry : tasksMap.entrySet()) {
            CJSJobDetail jobDetail = jobDetailEntry.getValue();
            Status status = jobDetail.getStatusProxy().getStatusResult();
            if (status.getStatus() == Status.StatusCode.FAULT) {
                Throwable cause = status.getFaultCause();
                if (cause == null) {
                    logger.error("Job {} failed", jobDetail.getUniqueId());
                    throw new OperationIncompleteException("Job "+ jobDetail.getUniqueId() + " failed");
                } else {
                    logger.error("Job {} error trace: ", jobDetail.getUniqueId(), cause);
                    throw new OperationIncompleteException("Job "+ jobDetail.getUniqueId() + " failed because " + cause.getMessage());
                }
            }
        }
    }
}
