package org.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class DSTTransitionChecker {

    private static final String CONFIG_FILE = "DSTTransitionChecker.config";
    private static final String JSON_FILE = "sampleTimezone.json";
    private static final String BACKUP_FILE = "sampleTimezone.json.backup";
    private static final String API_KEY = "7CWXLAEWFOEO";

    private static final Map<String, String> OFFSET_TO_TIMEZONE = new HashMap<>();

    static {
        OFFSET_TO_TIMEZONE.put("-12", "Pacific/Fiji");
        OFFSET_TO_TIMEZONE.put("-11", "Pacific/Midway");
        OFFSET_TO_TIMEZONE.put("-10", "Pacific/Honolulu");
        OFFSET_TO_TIMEZONE.put("-09", "America/Anchorage");
        OFFSET_TO_TIMEZONE.put("-08", "America/Los_Angeles");
        OFFSET_TO_TIMEZONE.put("-07", "America/Denver");
        OFFSET_TO_TIMEZONE.put("-06", "America/Chicago");
        OFFSET_TO_TIMEZONE.put("-05", "America/New_York");
        OFFSET_TO_TIMEZONE.put("-04", "America/Halifax");
        OFFSET_TO_TIMEZONE.put("-03", "America/Argentina/Buenos_Aires");
        OFFSET_TO_TIMEZONE.put("-02", "Atlantic/South_Georgia");
        OFFSET_TO_TIMEZONE.put("-01", "Atlantic/Azores");
        OFFSET_TO_TIMEZONE.put("00", "Europe/London");
        OFFSET_TO_TIMEZONE.put("01", "Europe/Vienna");
        OFFSET_TO_TIMEZONE.put("02", "Europe/Berlin");
        OFFSET_TO_TIMEZONE.put("03", "Europe/Moscow");
        OFFSET_TO_TIMEZONE.put("04", "Asia/Dubai");
        OFFSET_TO_TIMEZONE.put("05", "Asia/Karachi");
        OFFSET_TO_TIMEZONE.put("06", "Asia/Dhaka");
        OFFSET_TO_TIMEZONE.put("07", "Asia/Bangkok");
        OFFSET_TO_TIMEZONE.put("08", "Asia/Shanghai");
        OFFSET_TO_TIMEZONE.put("09", "Asia/Tokyo");
        OFFSET_TO_TIMEZONE.put("10", "Australia/Sydney");
        OFFSET_TO_TIMEZONE.put("11", "Pacific/Noumea");
        OFFSET_TO_TIMEZONE.put("12", "Pacific/Auckland");
    }



    public static void main(String[] args) {
        try {
            DSTTransitionChecker checker = new DSTTransitionChecker();
            checker.processTimezones();
        } catch (Exception e) {
            System.out.println("Error in main: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processTimezones() throws Exception {
        long currentUtc = Instant.now().getEpochSecond();
        String batchFile = loadConfig();
        List<Map<String, Object>> timezones = loadJson();

        for (Map<String, Object> tz : timezones) {
            long nextTransition = ((Number) tz.get("nextTransition")).longValue();
            String timezone = (String) tz.get("timezone");

            if (nextTransition > currentUtc + 300) {
                System.out.println(timezone + ": Transition is more than 5 minutes ahead. Skipping.");
            } else if (nextTransition < currentUtc - 300) {
                System.out.println(timezone + ": Transition is more than 5 minutes behind. Querying API.");
                Map<String, Object> apiData = queryTimeZoneDB(timezone);
                if (apiData != null) {
                    tz.putAll(apiData);
                }
            } else {
                System.out.println(timezone + ": Transition is within 5 minutes. Running batch.");
                runTransitionBatch(batchFile, timezone, (int) tz.get("transitionDirection"), (int) tz.get("currentOffset"));
            }

            Thread.sleep(2000);
        }

        backupJson();
        saveJson(timezones);
    }

    private String loadConfig() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(CONFIG_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("transitionBatch")) {
                    return line.split("=")[1].trim();
                }
            }
        }
        throw new RuntimeException("transitionBatch not found in config file.");
    }

    private List<Map<String, Object>> loadJson() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(new File(JSON_FILE), new TypeReference<>() {});
    }

    private void saveJson(List<Map<String, Object>> data) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(JSON_FILE), data);
    }

    private void backupJson() throws IOException {
        Files.copy(new File(JSON_FILE).toPath(), new File(BACKUP_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private Map<String, Object> queryTimeZoneDB(String timezone) {
        String offset = extractOffsetFromTimezone(timezone);
        if (offset == null) {
            System.err.println("Invalid timezone format: " + timezone);
            return null;
        }

        String apiUrl = buildTimeZoneDbUrl(offset);
        return fetchApiData(apiUrl, timezone);
    }

    private String extractOffsetFromTimezone(String timezone) {
        // Use a regex to match the offset in the format (GMT±HH:mm) and capture only the hour part
        Pattern pattern = Pattern.compile("\\(GMT([+-]?\\d{2}):\\d{2}\\).*");
        Matcher matcher = pattern.matcher(timezone);
        if (matcher.matches()) {
            String offset = matcher.group(1); // Extract the offset with sign, e.g., +05 or -05
            return offset.startsWith("+") ? offset.substring(1) : offset; // Remove the "+" sign
        }
        return null; // Return null if the format doesn't match
    }

    private String buildTimeZoneDbUrl(String offset) {
//        System.out.println(OFFSET_TO_TIMEZONE.get(offset.replace(":", "")));
        return "http://api.timezonedb.com/v2.1/get-time-zone?key=" + API_KEY +
                "&format=json&by=zone&zone=" + OFFSET_TO_TIMEZONE.get(offset.replace(":", ""));
    }

    private Map<String, Object> fetchApiData(String apiUrl, String timezone) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(apiUrl))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
//            System.out.println("Response = "+response.body());

            if (response.statusCode() != 200) {
                System.out.println("Failed to fetch data for timezone: " + timezone);
                return null;
            }

            return parseTimeZoneDbResponse(response.body());
        } catch (Exception e) {
            System.err.println("Error querying TimeZoneDB API for timezone: " + timezone);
            e.printStackTrace();
        }
        return null;
    }

    private Map<String, Object> parseTimeZoneDbResponse(String responseBody) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> data = mapper.readValue(responseBody, new TypeReference<>() {});
            long nextTransition = data.containsKey("nextTransition")
                    ? Instant.parse(data.get("nextTransition").toString()).getEpochSecond()
                    : 0;
            int currentOffset = Integer.parseInt(data.get("gmtOffset").toString()) / 3600;
//            System.out.println("data= "+data.get("dst"));
            int transitionDirection = Integer.parseInt((String) data.get("dst")) > 0 ? 1 : -1;

            return Map.of(
                    "nextTransition", nextTransition,
                    "transitionDirection", transitionDirection,
                    "currentOffset", currentOffset
            );
        } catch (Exception e) {
            System.err.println("Error parsing TimeZoneDB response: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    private void runTransitionBatch(String batchFile, String timezone, int direction, int offset) {
        System.out.println("Executing batch: " + batchFile + " " + timezone + " " + direction + " " + offset);
        try {
            ProcessBuilder pb = new ProcessBuilder(batchFile, timezone, String.valueOf(direction), String.valueOf(offset));
            pb.inheritIO();
            pb.start().waitFor();
        } catch (Exception e) {
            System.err.println("Failed to execute batch file.");
            e.printStackTrace();
        }
    }

    public void testProcessTimezones() throws Exception {
        processTimezones();
    }

}
