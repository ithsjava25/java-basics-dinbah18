package com.example;

import com.example.api.ElpriserAPI;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

public class Main {

    public static void main(String[] args) {
        if (args.length == 0 || contains(args, "--help")) {
            printHelp();
            return;
        }

        Map<String, String> flags = parseArgs(args);
        String zone = flags.get("zone");
        String dateStr = flags.get("date");
        String charging = flags.get("charging");
        boolean sorted = flags.containsKey("sorted");

        if (zone == null) {
            System.out.println("Missing required argument: --zone (SE1, SE2, SE3, SE4)");
            return;
        }

        ElpriserAPI.Prisklass prisklass;
        try {
            prisklass = ElpriserAPI.Prisklass.valueOf(zone);
        } catch (Exception e) {
            System.out.println("Invalid zone: " + zone + " (ogiltig zon)");
            return;
        }

        // --- Rätt tidszon (Europe/Stockholm) ---
        LocalDate date;
        if (dateStr == null) {
            date = LocalDate.now(ZoneId.of("Europe/Stockholm"));
        } else {
            try {
                date = LocalDate.parse(dateStr);
            } catch (Exception e) {
                System.out.println("Invalid date: " + dateStr + " (ogiltigt datum, format yyyy-MM-dd)");
                return;
            }
        }

        ElpriserAPI api = new ElpriserAPI(false);
        List<ElpriserAPI.Elpris> today = safe(api.getPriser(date, prisklass));

        // --- LADDNINGSLÄGE ---
        if (charging != null && !charging.isBlank()) {
            int hours = parseChargingHours(charging);
            if (hours <= 0) {
                System.out.println("Ogiltigt värde för --charging (använd 2h, 4h eller 8h)");
                return;
            }

            List<ElpriserAPI.Elpris> all = new ArrayList<>(today);
            List<ElpriserAPI.Elpris> tomorrow = safe(api.getPriser(date.plusDays(1), prisklass));

            if (isNextDay(tomorrow, date) && !isSameDay(tomorrow, date)) {
                all.addAll(tomorrow);
            }

            if (all.isEmpty()) {
                System.out.println("Ingen data / inga priser.");
                return;
            }

            ChargingResult best = findBestWindow(all, hours);
            String startStr = String.format("%02d:00", best.start().getHour());
            System.out.println("Påbörja laddning kl " + startStr);
            System.out.println("Medelpris för fönster: " + formatOreAdaptive(best.avgSekPerKWh()) + " öre");
            return;
        }

        // --- SORTERAT LÄGE ---
        if (sorted) {
            if (today.isEmpty()) {
                System.out.println("[]");
                return;
            }
            // Sortering i stigande ordning (billigast först) – så testerna går igenom
            today.stream()
                    .sorted(Comparator
                            .comparingDouble(ElpriserAPI.Elpris::sekPerKWh)
                            .thenComparing(ElpriserAPI.Elpris::timeStart))
                    .forEach(p -> System.out.println(hourSpan(p.timeStart()) + " " + formatOre2(p.sekPerKWh()) + " öre"));
            return;
        }

        // --- STANDARD: MIN/MAX/MEDEL ---
        if (today.isEmpty()) {
            System.out.println("Ingen data / inga priser.");
            return;
        }

        Optional<ElpriserAPI.Elpris> min = today.stream()
                .min(Comparator
                        .comparingDouble(ElpriserAPI.Elpris::sekPerKWh)
                        .thenComparing(ElpriserAPI.Elpris::timeStart));
        Optional<ElpriserAPI.Elpris> max = today.stream()
                .max(Comparator
                        .comparingDouble(ElpriserAPI.Elpris::sekPerKWh)
                        .thenComparing(ElpriserAPI.Elpris::timeStart));
        double avg = today.stream().mapToDouble(ElpriserAPI.Elpris::sekPerKWh).average().orElse(0.0);

        min.ifPresent(el ->
                System.out.println("Lägsta pris: " + hourSpan(el.timeStart()) + " " + formatOre2(el.sekPerKWh()) + " öre"));
        max.ifPresent(el ->
                System.out.println("Högsta pris: " + hourSpan(el.timeStart()) + " " + formatOre2(el.sekPerKWh()) + " öre"));
        System.out.println("Medelpris: " + formatOreAdaptive(avg) + " öre");
    }

    // ---------- Helpers ----------

    private static boolean contains(String[] arr, String s) {
        for (String a : arr) if (a.equalsIgnoreCase(s)) return true;
        return false;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--zone" -> { if (i + 1 < args.length) m.put("zone", args[++i]); }
                case "--date" -> { if (i + 1 < args.length) m.put("date", args[++i]); }
                case "--charging" -> { if (i + 1 < args.length) m.put("charging", args[++i]); }
                case "--sorted" -> m.put("sorted", "true");
                case "--help" -> m.put("help", "true");
            }
        }
        return m;
    }

    private static void printHelp() {
        System.out.println("Usage: --zone SE1|SE2|SE3|SE4 [--date YYYY-MM-DD] [--sorted] [--charging 2h|4h|8h]");
        System.out.println("Flags: --zone, --date, --charging, --sorted");
        System.out.println("Zoner: SE1, SE2, SE3, SE4");
    }

    private static List<ElpriserAPI.Elpris> safe(List<ElpriserAPI.Elpris> list) {
        return (list == null) ? List.of() : list;
    }

    private static boolean isSameDay(List<ElpriserAPI.Elpris> list, LocalDate day) {
        return !list.isEmpty() &&
                list.get(0).timeStart().toLocalDate().isEqual(day);
    }

    private static boolean isNextDay(List<ElpriserAPI.Elpris> list, LocalDate day) {
        return !list.isEmpty() &&
                list.get(0).timeStart().toLocalDate().isEqual(day.plusDays(1));
    }

    private static String hourSpan(ZonedDateTime start) {
        int h1 = start.getHour();
        int h2 = (h1 + 1) % 24;
        return String.format("%02d-%02d", h1, h2);
    }

    private static String formatOre2(double sekPerKWh) {
        double ore = sekPerKWh * 100.0;
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(new Locale("sv", "SE"));
        DecimalFormat df = new DecimalFormat("0.00", symbols);
        return df.format(ore);
    }

    private static String formatOreAdaptive(double sekPerKWh) {
        double ore = sekPerKWh * 100.0;
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(new Locale("sv", "SE"));
        DecimalFormat df = new DecimalFormat("0.##", symbols);
        return df.format(ore);
    }

    private static int parseChargingHours(String s) {
        if (s == null) return -1;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.endsWith("h")) t = t.substring(0, t.length() - 1);
        try {
            int h = Integer.parseInt(t);
            return (h == 2 || h == 4 || h == 8) ? h : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private record ChargingResult(ZonedDateTime start, double avgSekPerKWh) {}

    private static ChargingResult findBestWindow(List<ElpriserAPI.Elpris> prices, int windowHours) {
        List<ElpriserAPI.Elpris> list = new ArrayList<>(prices);
        list.sort(Comparator.comparing(ElpriserAPI.Elpris::timeStart));

        if (list.size() < windowHours) {
            ZonedDateTime start = list.get(0).timeStart();
            double avg = list.stream().mapToDouble(ElpriserAPI.Elpris::sekPerKWh).average().orElse(0.0);
            return new ChargingResult(start, avg);
        }

        double windowSum = 0;
        for (int i = 0; i < windowHours; i++) {
            windowSum += list.get(i).sekPerKWh();
        }
        double bestSum = windowSum;
        int bestStart = 0;

        for (int i = windowHours; i < list.size(); i++) {
            windowSum += list.get(i).sekPerKWh();
            windowSum -= list.get(i - windowHours).sekPerKWh();
            int start = i - windowHours + 1;

            if (windowSum < bestSum ||
                    (Math.abs(windowSum - bestSum) < 1e-9 &&
                            list.get(start).timeStart().isBefore(list.get(bestStart).timeStart()))) {
                bestSum = windowSum;
                bestStart = start;
            }
        }
        return new ChargingResult(list.get(bestStart).timeStart(), bestSum / windowHours);
    }

    public static void displaySortedPrices(List<ElpriserAPI.Elpris> priser) {
        if (priser == null || priser.isEmpty()) {
            System.out.println("[]");
            return;
        }
        // Samma sortering som i --sorted
        priser.stream()
                .sorted(Comparator
                        .comparingDouble(ElpriserAPI.Elpris::sekPerKWh)
                        .thenComparing(ElpriserAPI.Elpris::timeStart))
                .forEach(p -> System.out.println(hourSpan(p.timeStart()) + " " + formatOre2(p.sekPerKWh()) + " öre"));
    }
}
