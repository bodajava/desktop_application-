package com.examhalls.util;

import com.examhalls.service.StudentImportService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the admin's student roster sheet (.xlsx). Header row (any order, case-insensitive):
 * student_code, full_name, grade_level, section (optional), email (optional).
 */
public final class StudentSheetReader {

    private static final DataFormatter FORMATTER = new DataFormatter();

    private StudentSheetReader() {
    }

    public static List<StudentImportService.Row> read(File file) throws IOException {
        List<StudentImportService.Row> rows = new java.util.ArrayList<>();
        try (FileInputStream in = new FileInputStream(file); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                throw new IOException("The sheet has no header row");
            }
            Map<String, Integer> columns = new HashMap<>();
            for (Cell cell : header) {
                String key = normalize(text(cell));
                if (!key.isEmpty()) {
                    columns.put(key, cell.getColumnIndex());
                }
            }
            int codeCol = requireColumn(columns, "studentcode", "code", "id");
            int nameCol = requireColumn(columns, "fullname", "name", "studentname");
            int gradeCol = requireColumn(columns, "gradelevel", "grade");
            Integer sectionCol = optionalColumn(columns, "section", "class");
            Integer emailCol = optionalColumn(columns, "email");

            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isBlankRow(row)) {
                    continue;
                }
                String code = text(row.getCell(codeCol));
                String name = text(row.getCell(nameCol));
                String grade = text(row.getCell(gradeCol));
                String section = sectionCol == null ? null : text(row.getCell(sectionCol));
                String email = emailCol == null ? null : text(row.getCell(emailCol));
                rows.add(new StudentImportService.Row(code, name, grade, section, email));
            }
        }
        return rows;
    }

    private static int requireColumn(Map<String, Integer> columns, String... names) {
        Integer found = optionalColumn(columns, names);
        if (found == null) {
            throw new IllegalArgumentException("Missing required column (expected one of: " + String.join(", ", names) + ")");
        }
        return found;
    }

    private static Integer optionalColumn(Map<String, Integer> columns, String... names) {
        for (String n : names) {
            Integer idx = columns.get(n);
            if (idx != null) {
                return idx;
            }
        }
        return null;
    }

    private static boolean isBlankRow(Row row) {
        for (Cell cell : row) {
            if (!text(cell).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String header) {
        return header.trim().toLowerCase(Locale.ROOT).replaceAll("[ _-]", "");
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double v = cell.getNumericCellValue();
            return v == Math.floor(v) ? String.valueOf((long) v) : FORMATTER.formatCellValue(cell);
        }
        return FORMATTER.formatCellValue(cell).trim();
    }
}
