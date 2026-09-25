package com.examhalls.model;

/** Active room (V_ROOMS). */
public record Room(Long roomId, String roomCode, String building, int regularCapacity, int examCapacity,
                   RoomStatus status) {
}
