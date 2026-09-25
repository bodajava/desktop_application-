package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.RoomDao;
import com.examhalls.model.Room;
import com.examhalls.model.RoomStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class RoomDaoImpl extends JdbcSupport implements RoomDao {

    private static final String SELECT = """
            SELECT room_id, room_code, building, regular_capacity, exam_capacity, room_status FROM v_rooms
            """;

    public RoomDaoImpl(TransactionManager tx) {
        super(tx);
    }

    @Override
    public Optional<Room> findById(long id) {
        return queryOne(SELECT + " WHERE room_id = ?", ps -> ps.setLong(1, id), RoomDaoImpl::map);
    }

    @Override
    public Optional<Room> findByCode(String roomCode) {
        return queryOne(SELECT + " WHERE room_code = UPPER(TRIM(?))", ps -> ps.setString(1, roomCode), RoomDaoImpl::map);
    }

    @Override
    public List<Room> findAll() {
        return query(SELECT + " ORDER BY room_code", NO_PARAMS, RoomDaoImpl::map);
    }

    @Override
    public List<Room> findByStatus(RoomStatus status) {
        return query(SELECT + " WHERE room_status = ? ORDER BY room_code", ps -> ps.setString(1, status.name()),
                RoomDaoImpl::map);
    }

    @Override
    public long insert(Room r) {
        return insert("""
                INSERT INTO rooms (room_code, building, regular_capacity, exam_capacity, room_status)
                VALUES (?, ?, ?, ?, ?)
                """, "ROOM_ID", ps -> {
            ps.setString(1, r.roomCode());
            ps.setString(2, r.building());
            ps.setInt(3, r.regularCapacity());
            ps.setInt(4, r.examCapacity());
            ps.setString(5, (r.status() == null ? RoomStatus.AVAILABLE : r.status()).name());
        });
    }

    @Override
    public void update(Room r) {
        updateExactlyOne("""
                UPDATE rooms
                SET    room_code = ?, building = ?, regular_capacity = ?, exam_capacity = ?, room_status = ?
                WHERE  room_id = ? AND is_deleted = 'N'
                """, "Room", r.roomId(), ps -> {
            ps.setString(1, r.roomCode());
            ps.setString(2, r.building());
            ps.setInt(3, r.regularCapacity());
            ps.setInt(4, r.examCapacity());
            ps.setString(5, r.status().name());
            ps.setLong(6, r.roomId());
        });
    }

    /** Soft delete via V_ROOMS (refused if future exams use the room). */
    @Override
    public void delete(long id) {
        updateExactlyOne("DELETE FROM v_rooms WHERE room_id = ?", "Room", id, ps -> ps.setLong(1, id));
    }

    private static Room map(ResultSet rs) throws SQLException {
        return new Room(rs.getLong("room_id"), rs.getString("room_code"), rs.getString("building"),
                rs.getInt("regular_capacity"), rs.getInt("exam_capacity"),
                RoomStatus.valueOf(rs.getString("room_status")));
    }
}
