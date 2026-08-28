// Sundial.java
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Sundial {
    private static final double DEG = Math.PI / 180;
    private static final double RAD = 180 / Math.PI;

    @Parameter(names = "--lat", required = true)
    private double lat;
    @Parameter(names = "--lon", required = true)
    private double lon;
    @Parameter(names = "--date")
    private String dateStr;
    @Parameter(names = "--time")
    private String timeStr;
    @Parameter(names = "--height")
    private double height = 1.0;
    @Parameter(names = "--tz")
    private Double tzOffset;
    @Parameter(names = "--shadow")
    private boolean showShadow;
    @Parameter(names = "--day")
    private boolean dayMode;
    @Parameter(names = "--json")
    private String jsonFile;
    @Parameter(names = "--csv")
    private String csvFile;

    static class Result {
        double altitude, azimuth, shadowAz, solarTime, dec, hourAngle;
        Double shadowLen;
        boolean aboveHorizon;
    }

    private ZonedDateTime dateTime;
    private ZonedDateTime utcTime;

    public void init() {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (dateStr != null) {
            LocalDate d = LocalDate.parse(dateStr);
            now = ZonedDateTime.of(d, LocalTime.of(0,0), zone);
        }
        if (timeStr != null) {
            String[] parts = timeStr.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int s = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            now = now.withHour(h).withMinute(m).withSecond(s);
        }
        this.dateTime = now;
        double tz = tzOffset != null ? tzOffset : -(double)zone.getRules().getOffset(now.toInstant()).getTotalSeconds() / 3600;
        this.utcTime = now.minusSeconds((long)(tz * 3600));
    }

    private double julianDay(ZonedDateTime dt) {
        int y = dt.getYear();
        int m = dt.getMonthValue();
        double d = dt.getDayOfMonth() + dt.getHour()/24.0 + dt.getMinute()/1440.0 + dt.getSecond()/86400.0;
        if (m <= 2) { y--; m += 12; }
        int A = y / 100;
        int B = 2 - A + A / 4;
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + B - 1524.5;
    }

    private double solarDeclination(ZonedDateTime dt) {
        ZonedDateTime start = ZonedDateTime.of(dt.getYear(), 1, 1, 0, 0, 0, 0, dt.getZone());
        double n = Duration.between(start, dt).toDays() + 1;
        return -23.44 * Math.cos(DEG * (360.0/365.0) * (n + 10));
    }

    private double equationOfTime(ZonedDateTime dt) {
        ZonedDateTime start = ZonedDateTime.of(dt.getYear(), 1, 1, 0, 0, 0, 0, dt.getZone());
        double n = Duration.between(start, dt).toDays() + 1;
        double B = (360.0 / 365.0) * (n - 81);
        double B_rad = B * DEG;
        return 9.87 * Math.sin(2 * B_rad) - 7.53 * Math.cos(B_rad) - 1.5 * Math.sin(B_rad);
    }

    public Result calculate() {
        double eot = equationOfTime(utcTime);
        double utcHours = utcTime.getHour() + utcTime.getMinute()/60.0 + utcTime.getSecond()/3600.0;
        double solarTime = utcHours + lon/15.0 + eot/60.0;
        double hourAngle = (solarTime - 12) * 15 * DEG;
        double dec = solarDeclination(utcTime) * DEG;
        double latRad = lat * DEG;
        double sinAlt = Math.sin(latRad) * Math.sin(dec) + Math.cos(latRad) * Math.cos(dec) * Math.cos(hourAngle);
        double altitude = Math.asin(sinAlt);
        double azimuth = 0;
        if (altitude > 0.01) {
            double cosAz = (Math.sin(dec) - Math.sin(latRad) * sinAlt) / (Math.cos(latRad) * Math.cos(altitude) + 1e-9);
            azimuth = Math.acos(Math.max(-1, Math.min(1, cosAz)));
            if (hourAngle > 0) azimuth = -azimuth + 2*Math.PI;
            azimuth = (azimuth + 2*Math.PI) % (2*Math.PI);
        }
        Double shadowLen = altitude > 0.01 ? height / Math.tan(altitude) : null;
        double shadowAz = altitude > 0.01 ? (azimuth + Math.PI) % (2*Math.PI) * RAD : 0;
        Result res = new Result();
        res.altitude = altitude * RAD;
        res.azimuth = azimuth * RAD;
        res.shadowLen = shadowLen;
        res.shadowAz = shadowAz;
        res.solarTime = solarTime;
        res.dec = dec * RAD;
        res.hourAngle = hourAngle * RAD;
        res.aboveHorizon = altitude > 0;
        return res;
    }

    private void printResult(Result res) {
        boolean color = System.console() != null;
        String c = color ? "\u001B[36m" : "";
        String cY = color ? "\u001B[33m" : "";
        String cG = color ? "\u001B[32m" : "";
        String cB = color ? "\u001B[34m" : "";
        String cR = color ? "\u001B[31m" : "";
        String reset = color ? "\u001B[0m" : "";
        System.out.println(c + "☀️ Расчёт солнечной тени:" + reset);
        System.out.printf("  %sДата/время:%s %s%n", cY, reset, dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        System.out.printf("  %sШирота:%s %.4f°, Долгота: %.4f°%n", cY, reset, lat, lon);
        System.out.printf("  %sВысота гномона:%s %.2f м%n", cY, reset, height);
        if (res.aboveHorizon) {
            System.out.printf("  %sВысота Солнца:%s %.2f°%n", cG, reset, res.altitude);
            System.out.printf("  %sАзимут Солнца:%s %.2f°%n", cG, reset, res.azimuth);
            if (res.shadowLen != null) {
                System.out.printf("  %sДлина тени:%s %.2f м%n", cB, reset, res.shadowLen);
            } else {
                System.out.println("  " + (color ? "\u001B[33m" : "") + "Тень бесконечна (Солнце в зените)" + reset);
            }
            System.out.printf("  %sНаправление тени:%s %.2f°%n", cB, reset, res.shadowAz);
            if (showShadow) drawShadow(res);
        } else {
            System.out.println(cR + "  Солнце под горизонтом (ночь)" + reset);
        }
    }

    private void drawShadow(Result res) {
        int size = 15;
        int center = size / 2;
        double scale = 2.0;
        double shadowLen = res.shadowLen != null ? Math.min(res.shadowLen, 10) / scale : 0;
        double shadowAz = res.shadowAz * DEG;
        int x = (int)Math.round(center + shadowLen * Math.sin(shadowAz));
        int y = (int)Math.round(center - shadowLen * Math.cos(shadowAz));
        char[][] grid = new char[size][size];
        for (int i = 0; i < size; i++) Arrays.fill(grid[i], '░');
        grid[center][center] = '╋';
        if (x >= 0 && x < size && y >= 0 && y < size && res.shadowLen != null) {
            int steps = Math.max(Math.abs(x-center), Math.abs(y-center));
            for (int i = 0; i <= steps; i++) {
                int cx = (int)Math.round(center + (double)(x-center) * i / steps);
                int cy = (int)Math.round(center + (double)(y-center) * i / steps);
                if (cx >= 0 && cx < size && cy >= 0 && cy < size && grid[cy][cx] == '░') {
                    grid[cy][cx] = '█';
                }
            }
        }
        System.out.println("\n\u001B[36mВид сверху (N ↑):\u001B[0m");
        System.out.print("  ");
        for (int i = 0; i < size; i++) System.out.printf("%2d", i);
        System.out.println();
        for (int i = 0; i < size; i++) {
            System.out.printf("%2d ", i);
            for (int j = 0; j < size; j++) {
                char c = grid[i][j];
                if (c == '█') System.out.print("\u001B[32m█\u001B[0m");
                else if (c == '╋') System.out.print("\u001B[37m╋\u001B[0m");
                else System.out.print("\u001B[34m░\u001B[0m");
            }
            System.out.println();
        }
    }

    private void exportJson(Result res) throws IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("time", dateTime.toString());
        data.put("lat", lat);
        data.put("lon", lon);
        data.put("height", height);
        data.put("result", res);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.write(Paths.get(jsonFile), gson.toJson(data).getBytes());
    }

    private void exportCsv(Result res) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(csvFile))) {
            pw.println("time,altitude,azimuth,shadow_len,shadow_az");
            pw.printf("%s,%.2f,%.2f,%s,%.2f%n",
                dateTime.toString(), res.altitude, res.azimuth,
                res.shadowLen != null ? String.format("%.2f", res.shadowLen) : "",
                res.shadowAz);
        }
    }

    private List<Map<String, Object>> daySeries() {
        List<Map<String, Object>> results = new ArrayList<>();
        ZonedDateTime dt = dateTime.withHour(0).withMinute(0).withSecond(0);
        while (dt.getHour() < 24) {
            double tz = tzOffset != null ? tzOffset : -(double)dateTime.getOffset().getTotalSeconds() / 3600;
            ZonedDateTime utc = dt.minusSeconds((long)(tz * 3600));
            Sundial tmp = new Sundial();
            tmp.lat = lat; tmp.lon = lon; tmp.height = height; tmp.tzOffset = tz;
            tmp.dateTime = dt; tmp.utcTime = utc;
            Result res = tmp.calculate();
            Map<String, Object> entry = new HashMap<>();
            entry.put("time", dt.toString());
            entry.put("altitude", res.altitude);
            entry.put("azimuth", res.azimuth);
            entry.put("shadow_len", res.shadowLen);
            entry.put("shadow_az", res.shadowAz);
            entry.put("above_horizon", res.aboveHorizon);
            results.add(entry);
            dt = dt.plusHours(1);
        }
        return results;
    }

    public void run() throws Exception {
        init();
        if (dayMode) {
            List<Map<String, Object>> results = daySeries();
            System.out.println("\u001B[36m📊 Тень на " + dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE) + " (почасово):\u001B[0m");
            for (Map<String, Object> r : results) {
                if ((boolean)r.get("above_horizon")) {
                    String shadowLen = r.get("shadow_len") != null ? String.format("%6.2f", r.get("shadow_len")) : "     ∞";
                    System.out.printf("  %s  Alt: %5.1f°  Тень: %s м  Направление: %6.1f°%n",
                        r.get("time").toString().substring(11,16),
                        r.get("altitude"),
                        shadowLen,
                        r.get("shadow_az"));
                } else {
                    System.out.printf("  %s  Ночь%n", r.get("time").toString().substring(11,16));
                }
            }
            if (jsonFile != null) {
                Files.write(Paths.get(jsonFile), new GsonBuilder().setPrettyPrinting().create().toJson(results).getBytes());
                System.out.println("Экспортировано в " + jsonFile);
            }
            if (csvFile != null) {
                try (PrintWriter pw = new PrintWriter(new FileWriter(csvFile))) {
                    pw.println("time,altitude,azimuth,shadow_len,shadow_az");
                    for (Map<String, Object> r : results) {
                        pw.printf("%s,%.2f,%.2f,%s,%.2f%n",
                            r.get("time"), r.get("altitude"), r.get("azimuth"),
                            r.get("shadow_len") != null ? String.format("%.2f", r.get("shadow_len")) : "",
                            r.get("shadow_az"));
                    }
                }
                System.out.println("Экспортировано в " + csvFile);
            }
        } else {
            Result res = calculate();
            printResult(res);
            if (jsonFile != null) {
                exportJson(res);
                System.out.println("\u001B[32mЭкспортировано в " + jsonFile + "\u001B[0m");
            }
            if (csvFile != null) {
                exportCsv(res);
                System.out.println("\u001B[32mЭкспортировано в " + csvFile + "\u001B[0m");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Sundial sundial = new Sundial();
        JCommander.newBuilder().addObject(sundial).build().parse(args);
        sundial.run();
    }
}
