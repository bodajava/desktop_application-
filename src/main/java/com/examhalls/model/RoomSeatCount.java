package com.examhalls.model;

/** Seats taken by one exam in one room. */
public record RoomSeatCount(long examId, long roomId, int seated) {
}
