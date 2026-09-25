package com.examhalls.dao;

import com.examhalls.model.Room;
import com.examhalls.model.RoomStatus;

import java.util.List;
import java.util.Optional;

public interface RoomDao extends CrudDao<Room> {
    Optional<Room> findByCode(String roomCode);

    List<Room> findByStatus(RoomStatus status);
}
