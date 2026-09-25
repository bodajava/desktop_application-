package com.examhalls.service;

import com.examhalls.dao.ExamPeriodDao;
import com.examhalls.dao.ExamScheduleDao;
import com.examhalls.dao.ReportDao;
import com.examhalls.dao.RoomDao;
import com.examhalls.model.CellStatus;
import com.examhalls.model.ExamPeriod;
import com.examhalls.model.ExamSchedule;
import com.examhalls.model.OccupancyCell;
import com.examhalls.model.OccupancyGrid;
import com.examhalls.model.Room;
import com.examhalls.model.RoomSeatCount;
import com.examhalls.model.RoomStatus;
import com.examhalls.model.SupervisionRole;
import com.examhalls.model.SupervisionRoster;
import com.examhalls.security.Permission;
import com.examhalls.security.UserSession;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds the rooms x periods occupancy grid for a day (Screen 8, daily control sheet). */
public class OccupancyService {

    private final UserSession session;
    private final ExamScheduleDao examDao;
    private final ExamPeriodDao periodDao;
    private final RoomDao roomDao;
    private final ReportDao reportDao;

    public OccupancyService(UserSession session, ExamScheduleDao examDao, ExamPeriodDao periodDao, RoomDao roomDao,
                            ReportDao reportDao) {
        this.session = session;
        this.examDao = examDao;
        this.periodDao = periodDao;
        this.roomDao = roomDao;
        this.reportDao = reportDao;
    }

    /** The first exam day on or after {@code from}. */
    public Optional<LocalDate> nextExamDate(LocalDate from) {
        session.require(Permission.VIEW_ALL_SCHEDULES);
        return reportDao.nextExamDate(from);
    }

    public OccupancyGrid getGrid(LocalDate date) {
        session.require(Permission.VIEW_ALL_SCHEDULES);
        List<ExamPeriod> periods = periodDao.findAll();
        List<Room> rooms = roomDao.findAll();
        Map<Long, ExamSchedule> exams = examDao.findBetween(date, date).stream()
                .collect(Collectors.toMap(ExamSchedule::examId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<RoomSeatCount> seats = reportDao.seatCountsOn(date);
        List<SupervisionRoster> roster = reportDao.activeRosterOn(date);

        Map<String, OccupancyCell> cells = new HashMap<>();
        for (Room room : rooms) {
            for (ExamPeriod period : periods) {
                long pid = period.periodId();
                List<ExamSchedule> cellExams = new ArrayList<>();
                int seated = 0;
                for (RoomSeatCount sc : seats) {
                    ExamSchedule e = exams.get(sc.examId());
                    if (sc.roomId() == room.roomId() && e != null && e.periodId() == pid) {
                        cellExams.add(e);
                        seated += sc.seated();
                    }
                }
                List<SupervisionRoster> staff = roster.stream()
                        .filter(r -> r.roomId() == room.roomId() && exams.containsKey(r.examId())
                                && exams.get(r.examId()).periodId() == pid)
                        .toList();
                int required = seated == 0 ? 0 : requiredProctors(seated);
                cells.put(OccupancyGrid.key(room.roomId(), pid), new OccupancyCell(room.roomId(), pid, cellExams,
                        seated, room.examCapacity(), staff, required, statusOf(room, seated, staff, required)));
            }
        }
        return new OccupancyGrid(date, periods, rooms, cells);
    }

    /** Mirrors pkg_supervision.allocate_proctors: CEIL(students / 20), at least 1. */
    public static int requiredProctors(int seated) {
        int per = ExamManagementService.DEFAULT_STUDENTS_PER_PROCTOR;
        return Math.max(1, (seated + per - 1) / per);
    }

    private static CellStatus statusOf(Room room, int seated, List<SupervisionRoster> staff, int required) {
        if (seated == 0) {
            return room.status() == RoomStatus.AVAILABLE ? CellStatus.EMPTY : CellStatus.UNAVAILABLE;
        }
        long heads = staff.stream().filter(s -> s.roleType() == SupervisionRole.HEAD_OF_COMMITTEE).count();
        long proctors = staff.size() - heads;
        return heads == 0 || proctors < required ? CellStatus.UNDERSTAFFED : CellStatus.STAFFED;
    }
}
