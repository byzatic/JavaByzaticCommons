package io.github.byzatic.commons.schedulers.cron_job_scheduler;

import org.quartz.CronExpression;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** пример использования
 *
 *         try {
 *             // Пример использования с cron-выражением
 *             CronDateCalculator calculator = new CronDateCalculator("0 0/5 * * * ?"); // Каждые 5 минут
 *             Date now = new Date();
 *
 *             // Получение следующей даты
 *             Date nextExecution = calculator.getNextExecutionDate(now);
 *             System.out.println("Next execution date: " + nextExecution);
 *
 *             // Получение нескольких ближайших дат
 *             List<Date> nextExecutions = calculator.getNextExecutionDates(now, 5);
 *             System.out.println("Next 5 execution dates:");
 *             for (Date date : nextExecutions) {
 *                 System.out.println(date);
 *             }
 *
 *         } catch (ParseException e) {
 *             System.err.println("Invalid cron expression: " + e.getMessage());
 *         }
 */

public class CommonCronDateCalculator {

    private final CronExpression cronExpression;

    // Конструктор, принимающий cron-выражение
    public CommonCronDateCalculator(String cronExpressionString) throws ParseException {
        this.cronExpression = new CronExpression(cronExpressionString);
    }

    // Метод для получения следующей даты
    public Date getNextExecutionDate(Date afterDate) {
        return cronExpression.getNextValidTimeAfter(afterDate);
    }

    // Метод для получения нескольких ближайших дат
    public List<Date> getNextExecutionDates(Date afterDate, int count) {
        List<Date> dates = new ArrayList<>();
        Date nextDate = afterDate;

        for (int i = 0; i < count; i++) {
            nextDate = cronExpression.getNextValidTimeAfter(nextDate);
            if (nextDate == null) {
                break;
            }
            dates.add(nextDate);
        }
        return dates;
    }
}
