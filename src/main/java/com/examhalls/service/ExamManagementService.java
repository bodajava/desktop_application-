package com.examhalls.service;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.ExamScheduleDao;
import com.examhalls.dao.RoomDao;
import com.examhalls.dao.SeatingAllocationDao;
import com.examhalls.dao.SupervisionAuditDao;
import com.examhalls.dao.SupervisionRosterDao;
import com.examhalls.exception.AppException;
import com.examhalls.exception.ErrorCode;
import com.examhalls.exception.OracleErrorTranslator;
import com.examhalls.model.AllocationResult;
import com.examhalls.model.AttendanceStatus;
import com.examhalls.model.ExamOverview;
import com.examhalls.model.ExamSchedule;
import com.examhalls.model.Room;
import com.examhalls.model.RoomAllocationSummary;
import com.examhalls.model.SeatingAllocation;
import com.examhalls.model.SeatingResult;
import com.examhalls.model.SubstitutionResult;
import com.examhalls.model.SupervisionAudit;
import com.examhalls.model.SupervisionRoster;
import com.examhalls.model.TeacherCandidate;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.Permission;
import com.examhalls.security.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Exam-day operations backed by the PL/SQL packages (PKG_SEATING, PKG_SUPERVISION).
 *
 * <p>Every mutating call runs inside {@link TransactionManager#inTransaction}: the PL/SQL
 * procedures never commit, so the transaction here decides. If the caller already opened a
 * transaction (e.g. an integration test using {@code runAndRollback}), the call joins it.
 *
 * <p>Calls use anonymous PL/SQL blocks with named notation ({@code p_exam_id => ?}) so they keep
 * working if parameters are reordered in the package spec.
 */
public class ExamManagementService {

    private static final Logger log = LoggerFactory.getLogger(ExamManagementService.class);

    public static final int DEFAULT_STUDENTS_PER_PROCTOR = 20;

    @FunctionalInterface
    private interface CallBody<T> {
        T run(CallableStatement cs) throws SQLException;
    }

    private final TransactionManager tx;
    private final UserSession session;
    private final SeatingAllocationDao seatingDao;
    private final SupervisionRosterDao rosterDao;
    private final SupervisionAuditDao auditDao;
    private final ExamScheduleDao examDao;
    private final RoomDao roomDao;

    public ExamManagementService(TransactionManager tx, UserSession session, SeatingAllocationDao seatingDao,
                                 SupervisionRosterDao rosterDao, SupervisionAuditDao auditDao,
                                 ExamScheduleDao examDao, RoomDao roomDao) {
        this.tx = tx;
        this.session = session;
        this.seatingDao = seatingDao;
        this.rosterDao = rosterDao;
        this.auditDao = auditDao;
        this.examDao = examDao;
        this.roomDao = roomDao;
    }

    // ================================================================== schedule (read)

    /** Schedule grid rows between the two dates (inclusive). */
    public List<ExamOverview> getExamOverview(LocalDate from, LocalDate to) {
        session.require(Permission.VIEW_ALL_SCHEDULES);
        return examDao.findOverview(from, to);
    }

    public Optional<ExamSchedule> getExam(long examId) {
        session.require(Permission.VIEW_ALL_SCHEDULES);
        return examDao.findById(examId);
    }

    /** The signed-in teacher's upcoming duties (empty when the login is not linked to a teacher). */
    public List<SupervisionRoster> getMyUpcomingDuties() {
        AuthenticatedUser me = session.require(Permission.VIEW_OWN_SCHEDULE);
        if (me.teacherId() == null) {
            return List.of();
        }
        return rosterDao.findUpcomingByTeacher(me.teacherId(), LocalDate.now());
    }

    /**
     * The supervision matrix: every room the exam uses with its occupancy and all staff present in
     * that room during the slot (also staff attached to another exam sharing the room).
     */
    public List<RoomAllocationSummary> getRoomSummaries(long examId) {
        session.require(Permission.VIEW_ALL_SCHEDULES);
        List<SeatingAllocation> seats = seatingDao.findByExam(examId);
        Map<Long, List<SupervisionRoster>> staffByRoom = rosterDao.findActiveForExamRooms(examId).stream()
                .collect(Collectors.groupingBy(SupervisionRoster::roomId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, Room> rooms = roomDao.findAll().stream()
                .collect(Collectors.toMap(Room::roomId, Function.identity()));

        Map<Long, List<SeatingAllocation>> seatsByRoom = seats.stream()
                .collect(Collectors.groupingBy(SeatingAllocation::roomId, LinkedHashMap::new, Collectors.toList()));
        List<RoomAllocationSummary> result = new ArrayList<>();
        seatsByRoom.forEach((roomId, roomSeats) -> {
            Room room = rooms.get(roomId);
            result.add(new RoomAllocationSummary(roomId, roomSeats.get(0).roomCode(),
                    room != null ? room.building() : "", room != null ? room.examCapacity() : 0,
                    roomSeats.size(), (int) roomSeats.stream().filter(SeatingAllocation::hasSpecialNeeds).count(),
                    staffByRoom.getOrDefault(roomId, List.of())));
        });
        return result;
    }

    // ================================================================== seating

    /** Seats all students of the exam's grade level (replaces existing seating). */
    public SeatingResult generateSeating(long examId) {
        AuthenticatedUser user = session.require(Permission.GENERATE_SEATING);
        SeatingResult result = call("""
                BEGIN pkg_seating.generate_seating(p_exam_id => ?, p_seated => ?, p_rooms_used => ?); END;
                """, cs -> {
            cs.setLong(1, examId);
            cs.registerOutParameter(2, Types.INTEGER);
            cs.registerOutParameter(3, Types.INTEGER);
            cs.execute();
            return new SeatingResult(examId, cs.getInt(2), cs.getInt(3));
        });
        log.info("{} generated seating for exam {}: {}", user.username(), examId, result);
        return result;
    }

    public void clearSeating(long examId) {
        session.require(Permission.GENERATE_SEATING);
        call("BEGIN pkg_seating.clear_seating(p_exam_id => ?); END;", cs -> {
            cs.setLong(1, examId);
            cs.execute();
            return null;
        });
    }

    public void markAttendance(long seatingId, AttendanceStatus status) {
        session.require(Permission.MARK_ATTENDANCE);
        call("BEGIN pkg_seating.mark_attendance(p_seating_id => ?, p_status => ?); END;", cs -> {
            cs.setLong(1, seatingId);
            cs.setString(2, status.name());
            cs.execute();
            return null;
        });
    }

    public List<SeatingAllocation> getSeating(long examId) {
        session.require(Permission.VIEW_ALL_SCHEDULES);
        return seatingDao.findByExam(examId);
    }

    // ================================================================== proctor allocation

    public AllocationResult allocateProctors(long examId) {
        return allocateProctors(examId, DEFAULT_STUDENTS_PER_PROCTOR, false);
    }

    /**
     * Runs the smart allocation engine.
     *
     * @param allowPartial false = all-or-nothing (throws NO_ELIGIBLE_TEACHER listing the unfilled rooms);
     *                     true  = keep what could be filled and report {@link AllocationResult#unfilled()}
     */
    public AllocationResult allocateProctors(long examId, int studentsPerProctor, boolean allowPartial) {
        AuthenticatedUser user = session.require(Permission.ALLOCATE_PROCTORS);
        if (studentsPerProctor < 1) {
            throw validation("studentsPerProctor");
        }
        AllocationResult result = call("""
                BEGIN
                    pkg_supervision.allocate_proctors(
                        p_exam_id              => ?,
                        p_assigned             => ?,
                        p_unfilled             => ?,
                        p_students_per_proctor => ?,
                        p_allow_partial        => ?);
                END;
                """, cs -> {
            cs.setLong(1, examId);
            cs.registerOutParameter(2, Types.INTEGER);
            cs.registerOutParameter(3, Types.INTEGER);
            cs.setInt(4, studentsPerProctor);
            cs.setString(5, allowPartial ? "Y" : "N");
            cs.execute();
            return new AllocationResult(examId, cs.getInt(2), cs.getInt(3));
        });
        log.info("{} allocated proctors for exam {}: {}", user.username(), examId, result);
        return result;
    }

    /** Cancels all active assignments of the exam (hours refunded). Returns how many were cancelled. */
    public int cancelAllocation(long examId) {
        AuthenticatedUser user = session.require(Permission.ALLOCATE_PROCTORS);
        int cancelled = call("BEGIN pkg_supervision.cancel_allocation(p_exam_id => ?, p_cancelled => ?); END;", cs -> {
            cs.setLong(1, examId);
            cs.registerOutParameter(2, Types.INTEGER);
            cs.execute();
            return cs.getInt(2);
        });
        log.info("{} cancelled {} assignment(s) of exam {}", user.username(), cancelled, examId);
        return cancelled;
    }

    public List<SupervisionRoster> getActiveRoster(long examId) {
        session.require(Permission.VIEW_ALL_SCHEDULES);
        return rosterDao.findActiveByExam(examId);
    }

    /** Every active teacher for this exam/room, eligible ones first, with the reason when not eligible. */
    public List<TeacherCandidate> getCandidates(long examId, long roomId) {
        session.require(Permission.ALLOCATE_PROCTORS);
        return call("BEGIN ? := pkg_supervision.get_candidates(p_exam_id => ?, p_room_id => ?); END;", cs -> {
            cs.registerOutParameter(1, Types.REF_CURSOR);
            cs.setLong(2, examId);
            cs.setLong(3, roomId);
            cs.execute();
            List<TeacherCandidate> list = new ArrayList<>();
            try (ResultSet rs = cs.getObject(1, ResultSet.class)) {
                while (rs.next()) {
                    list.add(new TeacherCandidate(rs.getLong("teacher_id"), rs.getString("teacher_code"),
                            rs.getString("full_name"), rs.getString("dept_name"), rs.getBigDecimal("hours_balance"),
                            rs.getString("eligibility")));
                }
            }
            return list;
        });
    }

    // ================================================================== emergency substitution

    /** Read-only preview of who the 1-click substitution would pick. */
    public OptionalLong suggestSubstitute(long rosterId) {
        session.require(Permission.SUBSTITUTE_PROCTOR);
        return call("BEGIN ? := pkg_supervision.find_best_substitute(p_roster_id => ?); END;", cs -> {
            cs.registerOutParameter(1, Types.NUMERIC);
            cs.setLong(2, rosterId);
            cs.execute();
            long id = cs.getLong(1);
            return cs.wasNull() ? OptionalLong.empty() : OptionalLong.of(id);
        });
    }

    /**
     * Emergency 1-click substitution. The signed-in user is recorded in SUPERVISION_AUDIT.EXECUTED_BY.
     *
     * @param substituteTeacherId empty = pick the best eligible teacher automatically
     */
    public SubstitutionResult substituteProctor(long rosterId, OptionalLong substituteTeacherId, String reason) {
        AuthenticatedUser user = session.require(Permission.SUBSTITUTE_PROCTOR);
        if (reason == null || reason.isBlank()) {
            throw validation("reason");
        }
        if (reason.length() > 300) {
            throw validation("reason (max 300)");
        }
        SubstitutionResult result = call("""
                BEGIN
                    pkg_supervision.replace_proctor(
                        p_roster_id     => ?,
                        p_executed_by   => ?,
                        p_reason        => ?,
                        p_substitute_id => ?,
                        p_new_roster_id => ?,
                        p_audit_id      => ?);
                END;
                """, cs -> {
            cs.setLong(1, rosterId);
            cs.setLong(2, user.userId());
            cs.setString(3, reason.trim());
            if (substituteTeacherId.isPresent()) {
                cs.setLong(4, substituteTeacherId.getAsLong());
            } else {
                cs.setNull(4, Types.NUMERIC);
            }
            cs.registerOutParameter(4, Types.NUMERIC);   // IN OUT: the teacher actually assigned
            cs.registerOutParameter(5, Types.NUMERIC);
            cs.registerOutParameter(6, Types.NUMERIC);
            cs.execute();
            return new SubstitutionResult(rosterId, cs.getLong(5), cs.getLong(4), cs.getLong(6));
        });
        log.info("{} substituted roster {} -> teacher {} (audit {}): {}", user.username(), rosterId,
                result.substituteTeacherId(), result.auditId(), reason);
        return result;
    }

    public List<SupervisionAudit> getAuditTrail(long examId) {
        session.require(Permission.VIEW_AUDIT);
        return auditDao.findByExam(examId);
    }

    public Optional<SupervisionRoster> getRosterEntry(long rosterId) {
        session.require(Permission.VIEW_ALL_SCHEDULES);
        return rosterDao.findById(rosterId);
    }

    // ================================================================== plumbing

    private <T> T call(String sql, CallBody<T> body) {
        try {
            return tx.inTransaction(() -> {
                try (TransactionManager.ConnectionHandle h = tx.connection();
                     CallableStatement cs = h.get().prepareCall(sql)) {
                    return body.run(cs);
                }
            });
        } catch (SQLException e) {
            throw OracleErrorTranslator.translate(e);
        }
    }

    private static AppException validation(String field) {
        return new AppException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.messageKey(),
                "invalid " + field, null, field);
    }
}
