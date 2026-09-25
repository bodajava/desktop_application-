package com.examhalls.service;

import com.examhalls.dao.ExamScheduleDao;
import com.examhalls.dao.ReportDao;
import com.examhalls.dao.RoomDao;
import com.examhalls.model.AllocationState;
import com.examhalls.model.DashboardData;
import com.examhalls.model.DayCapacity;
import com.examhalls.model.DeptWorkload;
import com.examhalls.model.ExamOverview;
import com.examhalls.model.ExamSchedule;
import com.examhalls.model.FocusExamRow;
import com.examhalls.model.Room;
import com.examhalls.model.RoomSeatCount;
import com.examhalls.model.RoomStatus;
import com.examhalls.model.SubstitutionFeedItem;
import com.examhalls.model.SupervisionRole;
import com.examhalls.model.SupervisionRoster;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.Permission;
import com.examhalls.security.UserSession;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The executive dashboard: KPIs, chart series and the focus-day panels, computed in memory from
 * six read-only queries (exam overview, rooms, seats, roster, department workload, audit feed),
 * whatever the number of exam days. Nothing here writes to the database.
 */
public class DashboardService {

    /** How far ahead "active" exams are counted. */
    static final int HORIZON_DAYS = 366;
    static final int FEED_SIZE = 6;

    private final UserSession session;
    private final RoomDao roomDao;
    private final ExamScheduleDao examDao;
    private final ReportDao reportDao;

    public DashboardService(UserSession session, RoomDao roomDao, ExamScheduleDao examDao, ReportDao reportDao) {
        this.session = session;
        this.roomDao = roomDao;
        this.examDao = examDao;
        this.reportDao = reportDao;
    }

    public DashboardData getDashboard() {
        return getDashboard(LocalDate.now());
    }

    /** Room + exam day + period: the unit that shares staff (see pkg_supervision.allocate_proctors). */
    private record RoomSession(LocalDate date, long periodId, long roomId) {
    }

    private static final class Staffing {
        int seated;
        int heads;
        int proctors;

        int requiredProctors() {
            return OccupancyService.requiredProctors(seated);
        }

        int required() {
            return 1 + requiredProctors();
        }

        int filled() {
            return Math.min(1, heads) + Math.min(proctors, requiredProctors());
        }

        boolean nobody() {
            return heads + proctors == 0;
        }
    }

    public DashboardData getDashboard(LocalDate today) {
        AuthenticatedUser user = session.require(Permission.VIEW_ALL_SCHEDULES);
        LocalDate horizon = today.plusDays(HORIZON_DAYS);

        List<ExamOverview> exams = new ArrayList<>(examDao.findOverview(today, horizon));
        exams.sort(Comparator.comparing((ExamOverview o) -> o.exam().examDate())
                .thenComparing(o -> o.exam().slotStart(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(o -> o.exam().courseCode()));
        Map<Long, ExamSchedule> examById = new HashMap<>();
        exams.forEach(o -> examById.put(o.exam().examId(), o.exam()));

        // ---- staffing per room session
        Map<RoomSession, Staffing> sessions = new HashMap<>();
        Map<Long, Set<RoomSession>> sessionsByExam = new HashMap<>();
        for (RoomSeatCount sc : reportDao.seatCountsBetween(today, horizon)) {
            ExamSchedule e = examById.get(sc.examId());
            if (e == null || sc.seated() == 0) {
                continue;
            }
            RoomSession key = new RoomSession(e.examDate(), e.periodId(), sc.roomId());
            sessions.computeIfAbsent(key, k -> new Staffing()).seated += sc.seated();
            sessionsByExam.computeIfAbsent(e.examId(), k -> new LinkedHashSet<>()).add(key);
        }
        List<SupervisionRoster> roster = reportDao.activeRosterBetween(today, horizon);
        for (SupervisionRoster r : roster) {
            ExamSchedule e = examById.get(r.examId());
            Staffing s = e == null ? null : sessions.get(new RoomSession(e.examDate(), e.periodId(), r.roomId()));
            if (s == null) {
                continue;   // staff in a room without seated students: not a position
            }
            if (r.roleType() == SupervisionRole.HEAD_OF_COMMITTEE) {
                s.heads++;
            } else {
                s.proctors++;
            }
        }
        int required = 0, filled = 0, unstaffed = 0, partial = 0;
        for (Staffing s : sessions.values()) {
            required += s.required();
            filled += s.filled();
            if (s.nobody()) {
                unstaffed++;
            } else if (s.filled() < s.required()) {
                partial++;
            }
        }

        // ---- per exam state and per day capacity
        Map<AllocationState, Integer> allocation = new EnumMap<>(AllocationState.class);
        for (AllocationState st : AllocationState.values()) {
            allocation.put(st, 0);
        }
        Map<Long, AllocationState> stateByExam = new HashMap<>();
        for (ExamOverview o : exams) {
            AllocationState st = stateOf(o, sessionsByExam.getOrDefault(o.exam().examId(), Set.of()), sessions);
            stateByExam.put(o.exam().examId(), st);
            allocation.merge(st, 1, Integer::sum);
        }

        int slotCapacity = roomDao.findAll().stream()
                .filter(r -> r.status() == RoomStatus.AVAILABLE).mapToInt(Room::examCapacity).sum();
        Map<LocalDate, List<ExamOverview>> byDay = new TreeMap<>();
        exams.forEach(o -> byDay.computeIfAbsent(o.exam().examDate(), d -> new ArrayList<>()).add(o));
        List<DayCapacity> days = new ArrayList<>();
        byDay.forEach((day, list) -> {
            int periods = (int) list.stream().map(o -> o.exam().periodId()).distinct().count();
            days.add(new DayCapacity(day, periods, slotCapacity * periods,
                    list.stream().mapToInt(ExamOverview::enrolled).sum(),
                    list.stream().mapToInt(ExamOverview::seated).sum()));
        });

        // ---- focus day
        LocalDate focusDay = byDay.isEmpty() ? null : byDay.keySet().iterator().next();
        List<FocusExamRow> focusExams = new ArrayList<>();
        if (focusDay != null) {
            for (ExamOverview o : byDay.get(focusDay)) {
                Set<RoomSession> own = sessionsByExam.getOrDefault(o.exam().examId(), Set.of());
                focusExams.add(new FocusExamRow(o.exam(), o.enrolled(), o.seated(), own.size(),
                        own.stream().mapToInt(k -> sessions.get(k).filled()).sum(),
                        own.stream().mapToInt(k -> sessions.get(k).required()).sum(),
                        stateByExam.get(o.exam().examId())));
            }
        }
        Set<Long> onDuty = new HashSet<>();
        roster.stream().filter(r -> r.examDate().equals(focusDay)).forEach(r -> onDuty.add(r.teacherId()));

        List<DeptWorkload> departments = reportDao.departmentWorkload();
        int activeTeachers = departments.stream().mapToInt(DeptWorkload::teachers).sum();

        boolean auditVisible = user.can(Permission.VIEW_AUDIT);
        List<SubstitutionFeedItem> feed = auditVisible ? reportDao.recentSubstitutions(FEED_SIZE) : List.of();

        return new DashboardData(today, focusDay, exams.size(), focusExams.size(),
                exams.stream().mapToInt(ExamOverview::enrolled).sum(),
                exams.stream().mapToInt(ExamOverview::seated).sum(),
                days.stream().mapToInt(DayCapacity::capacity).sum(),
                required, filled, unstaffed, partial, activeTeachers, onDuty.size(),
                List.copyOf(days), allocation, departments, List.copyOf(focusExams), auditVisible, feed);
    }

    private static AllocationState stateOf(ExamOverview o, Set<RoomSession> own, Map<RoomSession, Staffing> sessions) {
        if (o.seated() == 0 || own.isEmpty()) {
            return AllocationState.NOT_SEATED;
        }
        if (own.stream().allMatch(k -> sessions.get(k).nobody())) {
            return AllocationState.UNASSIGNED;
        }
        return own.stream().allMatch(k -> sessions.get(k).filled() == sessions.get(k).required())
                ? AllocationState.FULL : AllocationState.PARTIAL;
    }
}
