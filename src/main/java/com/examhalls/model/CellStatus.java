package com.examhalls.model;

/** Colour-coded state of one room during one period in the occupancy grid. */
public enum CellStatus {
    EMPTY,          // no exam in the room
    UNAVAILABLE,    // room under maintenance / unavailable
    UNDERSTAFFED,   // students seated but head or proctors missing
    STAFFED         // seated and fully staffed
}
