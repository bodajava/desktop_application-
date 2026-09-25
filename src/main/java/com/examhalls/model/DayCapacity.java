package com.examhalls.model;

import java.time.LocalDate;

/**
 * One exam day for the capacity chart. {@code capacity} is the exam capacity of every available
 * room multiplied by the number of periods used that day (rooms are reused in each period).
 */
public record DayCapacity(LocalDate date, int periods, int capacity, int enrolled, int seated) {
}
