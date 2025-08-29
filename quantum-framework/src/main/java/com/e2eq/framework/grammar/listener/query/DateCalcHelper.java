package com.e2eq.framework.grammar.listener.query;

import org.joda.time.DateTime;

import java.time.DayOfWeek;
import java.util.Date;

public class DateCalcHelper {

    /**
     * Calculates the start of the current quarter given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfCurrentQuarter(DateTime dateTime){
        int dateTimeQuarter = ((dateTime.getMonthOfYear() - 1) / 3) + 1;
        int firstMonthInQ = ((dateTimeQuarter - 1) * 3) + 1;
        return dateTime.withMonthOfYear(firstMonthInQ).dayOfMonth().withMinimumValue().withTimeAtStartOfDay().toDate();
    }

    /**
     * Calculates the start of the next quarter given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfNextQuarter(DateTime dateTime){
        int dateTimeQuarter = ((dateTime.getMonthOfYear() - 1) / 3) + 1;
        int nextQuarter = dateTimeQuarter + 1;
        int year = dateTime.getYear();

        if (nextQuarter == 5) {
            nextQuarter = 1;
            //also its next year
            year = year + 1;
        }
        int firstMonthInQ = ((nextQuarter - 1) * 3) + 1;
        return dateTime.withMonthOfYear(firstMonthInQ).withYear(year).dayOfMonth()
                .withMinimumValue().withTimeAtStartOfDay().toDate();
    }

    /**
     * Calculates the start of the previous quarter given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfPreviousQuarter(DateTime dateTime){
        int dateTimeQuarter = ((dateTime.getMonthOfYear() - 1) / 3) + 1;
        int lastQuarter = dateTimeQuarter - 1;
        int year = dateTime.getYear();

        if (lastQuarter == 0) {
            lastQuarter = 4;
            //also its last year
            year = year - 1;
        }
        int firstMonthInQ = ((lastQuarter - 1) * 3) + 1;
        return dateTime.withMonthOfYear(firstMonthInQ).withYear(year).dayOfMonth()
                .withMinimumValue().withTimeAtStartOfDay().toDate();
    }

    /**
     * Calculates the start of the current year given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfCurrentYear(DateTime dateTime){
        return dateTime.dayOfYear().withMinimumValue().withTimeAtStartOfDay().toDate();
    }

    /**
     * Calculates the start of the previous year given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfPreviousYear(DateTime dateTime){
        return dateTime.minusYears(1).dayOfYear().withMinimumValue().withTimeAtStartOfDay().toDate();
    }

    /**
     * Calculates the start of the next year given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfNextYear(DateTime dateTime){
        return dateTime.plusYears(1).dayOfYear().withMinimumValue().withTimeAtStartOfDay().toDate();
    }

    /**
     * Calculates the start of the current hour the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfCurrentHour(DateTime dateTime){
        return dateTime.hourOfDay().roundFloorCopy().toDate();
    }

    /**
     * Calculates the start of the next hour given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfNextHour(DateTime dateTime){
        return dateTime.hourOfDay().roundCeilingCopy().toDate();
    }

    /**
     * Calculates the start of the previous hour given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfPreviousHour(DateTime dateTime){
        return dateTime.minusHours(1).hourOfDay().roundFloorCopy().toDate();
    }

    /**
     * Calculates the start of the current day given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfCurrentDay(DateTime dateTime){
        return dateTime.withTimeAtStartOfDay().toDate();
    }

    /**
     * Calculates the start of the next day given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfNextDay(DateTime dateTime){
        return dateTime.plusDays(1).withTimeAtStartOfDay().toDate();
    }

    /**
     * Calculates the start of the previous day given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfPreviousDay(DateTime dateTime){
        return dateTime.minusDays(1).withTimeAtStartOfDay().toDate();
    }

    /**
     * Calculates the start of the current week given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfCurrentWeek(DateTime dateTime){
        DateTime sunday = null;
        if (dateTime.getDayOfWeek() == DayOfWeek.SUNDAY.getValue()){
            //day is Sunday
            sunday = dateTime;
        } else {
            sunday = dateTime.minusDays(dateTime.getDayOfWeek());
        }
        return sunday.withTimeAtStartOfDay().toDate();
    }

    /**
     * Calculates the start of the next week given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfNextWeek(DateTime dateTime){
        DateTime nextWeek = dateTime.plusWeeks(1);
        DateTime sunday = null;
        if (nextWeek.getDayOfWeek() == DayOfWeek.SUNDAY.getValue()){
            //day is Sunday
            sunday = nextWeek;
        } else {
            sunday = nextWeek.minusDays(nextWeek.getDayOfWeek());
        }
        return sunday.withTimeAtStartOfDay().toDate();
    }

    /**
     * Calculates the start of the previous given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfPreviousWeek(DateTime dateTime){
        DateTime previousWeek = dateTime.minusWeeks(1);
        DateTime sunday = null;
        if (previousWeek.getDayOfWeek() == DayOfWeek.SUNDAY.getValue()){
            //day is Sunday
            sunday = previousWeek;
        } else {
            sunday = previousWeek.minusDays(previousWeek.getDayOfWeek());
        }
        return sunday.withTimeAtStartOfDay().toDate();
    }

    /**
     * Calculates the start of the current month given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfCurrentMonth(DateTime dateTime){
        return dateTime.dayOfMonth().withMinimumValue().withTimeAtStartOfDay().toDate();
    }

    /**
     * Calculates the start of the previous month given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfPreviousMonth(DateTime dateTime){
        return dateTime.minusMonths(1).dayOfMonth().withMinimumValue().withTimeAtStartOfDay().toDate();
    }

    /**
     * Calculates the start of the next month given the dateTime passed in.
     *
     * @param dateTime - DateTime object
     * @return - Date
     */
    public static Date getStartOfNextMonth(DateTime dateTime){
        return dateTime.plusMonths(1).dayOfMonth().withMinimumValue().withTimeAtStartOfDay().toDate();
    }

}