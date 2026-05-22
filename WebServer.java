import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WebServer {
    private static final int PORT = 8080;
    private static final LostFoundManager MANAGER = new LostFoundManager();
    private static final Path WEB_ROOT = Path.of("web");

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new StaticHandler());
        server.createContext("/api/items", new ItemsHandler());
        server.createContext("/api/items/search", new SearchHandler());
        server.createContext("/api/items/claim", new ClaimHandler());
        server.setExecutor(null);
        server.start();

        System.out.println("Lost & Found web app running at http://localhost:" + PORT);
        System.out.println("Press Ctrl+C to stop.");
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendError(HttpExchange ex, int status, String message) throws IOException {
        sendJson(ex, status, "{\"error\":\"" + escapeJson(message) + "\"}");
    }

    private static Map<String, String> parseFormBody(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) {
            return map;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = urlDecode(pair.substring(0, eq));
                String value = urlDecode(pair.substring(eq + 1));
                map.put(key, value);
            }
        }
        return map;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value.replace("+", " "), StandardCharsets.UTF_8);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String itemsToJson(List<Item> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{')
                    .append("\"id\":").append(it.id).append(',')
                    .append("\"name\":\"").append(escapeJson(it.name)).append("\",")
                    .append("\"category\":\"").append(escapeJson(it.category)).append("\",")
                    .append("\"location\":\"").append(escapeJson(it.location)).append("\",")
                    .append("\"date\":\"").append(escapeJson(it.date)).append("\",")
                    .append("\"description\":\"").append(escapeJson(it.description)).append("\",")
                    .append("\"claimed\":").append(it.claimed)
                    .append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    static class ItemsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                return;
            }

            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 200, itemsToJson(MANAGER.getAllItems()));
                return;
            }

            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                Map<String, String> form = parseFormBody(readBody(ex));
                String name = form.getOrDefault("name", "").trim();
                String category = form.getOrDefault("category", "").trim();
                String location = form.getOrDefault("location", "").trim();
                String date = form.getOrDefault("date", "").trim();
                String description = form.getOrDefault("description", "").trim();

                if (name.isEmpty() || category.isEmpty() || location.isEmpty() || date.isEmpty() || description.isEmpty()) {
                    sendError(ex, 400, "All fields are required.");
                    return;
                }

                try {
                    MANAGER.addItem(name, category, location, date, description);
                    sendJson(ex, 200, "{\"message\":\"Item added and list sorted.\"}");
                } catch (IllegalStateException e) {
                    sendError(ex, 400, e.getMessage());
                }
                return;
            }

            sendError(ex, 405, "Method not allowed.");
        }
    }

    static class SearchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "Method not allowed.");
                return;
            }

            String query = ex.getRequestURI().getQuery();
            String name = "";
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("name=")) {
                        name = urlDecode(part.substring(5)).trim();
                        break;
                    }
                }
            }

            if (name.isEmpty()) {
                sendError(ex, 400, "Name parameter is required.");
                return;
            }

            List<Item> matches = MANAGER.searchByName(name);
            sendJson(ex, 200, "{\"items\":" + itemsToJson(matches) + "}");
        }
    }

    static class ClaimHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "Method not allowed.");
                return;
            }

            Map<String, String> form = parseFormBody(readBody(ex));
            String idText = form.getOrDefault("id", "").trim();
            String verify = form.getOrDefault("verifyDescription", "").trim();
            String lostDate = form.getOrDefault("dateInput", "").trim();

            if (idText.isEmpty() || verify.isEmpty() || lostDate.isEmpty()) {
                sendError(ex, 400, "ID, verification description, and date are required.");
                return;
            }

            int id;
            try {
                id = Integer.parseInt(idText);
            } catch (NumberFormatException e) {
                sendError(ex, 400, "Item ID must be a number.");
                return;
            }

            String result = MANAGER.claimItem(id, verify, lostDate);
            boolean success = result.contains("marked as claimed");
            sendJson(ex, 200, "{\"success\":" + success + ",\"message\":\"" + escapeJson(result) + "\"}");
        }
    }

    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendError(ex, 405, "Method not allowed.");
                return;
            }

            String path = ex.getRequestURI().getPath();
            if ("/".equals(path)) {
                path = "/index.html";
            }

            Path file = WEB_ROOT.resolve(path.substring(1)).normalize();
            if (!file.startsWith(WEB_ROOT) || !Files.exists(file) || Files.isDirectory(file)) {
                sendError(ex, 404, "Not found.");
                return;
            }

            String contentType = contentType(file.getFileName().toString());
            byte[] bytes = Files.readAllBytes(file);
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }

        private String contentType(String name) {
            if (name.endsWith(".html")) return "text/html; charset=UTF-8";
            if (name.endsWith(".css")) return "text/css; charset=UTF-8";
            if (name.endsWith(".js")) return "application/javascript; charset=UTF-8";
            return "application/octet-stream";
        }
    }
}

class Item {
    int id;
    String name;
    String category;
    String location;
    String date;
    String description;
    boolean claimed;

    Item(int id, String name, String category, String location, String date, String description) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.location = location;
        this.date = date;
        this.description = description;
    }
}

class LostFoundManager {
    private static final int MAX = 50;
    private static final Path DATA_FILE = Path.of("data.json");
    private final List<Item> items = new ArrayList<>();
    private int nextId = 1;

    LostFoundManager() {
        load();
    }

    void addItem(String name, String category, String location, String date, String description) {
        if (items.size() >= MAX) {
            throw new IllegalStateException("Storage full.");
        }
        items.add(new Item(nextId++, name, category, location, date, description));
        insertionSort();
        save();
    }

    List<Item> getAllItems() {
        return List.copyOf(items);
    }

    List<Item> searchByName(String name) {
        List<Item> matches = new ArrayList<>();
        for (Item item : items) {
            if (item.name.equalsIgnoreCase(name)) {
                matches.add(item);
            }
        }
        return matches;
    }

    String claimItem(int id, String verifyDescription, String dateInput) {
        int idx = binarySearch(id);
        if (idx == -1) return "Item ID " + id + " not found.";

        Item item = items.get(idx);
        if (item.claimed) return "Already claimed.";

        String desc = item.description.toLowerCase(Locale.ROOT);
        String[] words = verifyDescription.toLowerCase(Locale.ROOT).trim().split("\\s+");
        int matchCount = 0;
        for (String word : words) {
            if (!word.isEmpty() && desc.contains(word)) matchCount++;
        }

        if (matchCount < 2) return "Verification failed. Provide more specific details.";
        if (!item.date.equals(dateInput)) return "Date mismatch. Verification failed.";

        item.claimed = true;
        save();
        return "Item '" + item.name + "' marked as claimed.";
    }

    private void save() {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            sb.append("  {")
              .append("\"id\":").append(it.id).append(",")
              .append("\"name\":\"").append(escapeJson(it.name)).append("\",")
              .append("\"category\":\"").append(escapeJson(it.category)).append("\",")
              .append("\"location\":\"").append(escapeJson(it.location)).append("\",")
              .append("\"date\":\"").append(escapeJson(it.date)).append("\",")
              .append("\"description\":\"").append(escapeJson(it.description)).append("\",")
              .append("\"claimed\":").append(it.claimed)
              .append(i < items.size() - 1 ? "},\n" : "}\n");
        }
        sb.append("]");
        try {
            Files.writeString(DATA_FILE, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to save data: " + e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(DATA_FILE)) return;
        try {
            String json = Files.readString(DATA_FILE, StandardCharsets.UTF_8).trim();
            if (json.isEmpty() || json.equals("[]")) return;
            // strip outer brackets
            json = json.substring(1, json.lastIndexOf(']'));
            for (String block : json.split("\\},\\s*\\{")) {
                block = block.replace("{", "").replace("}", "").trim();
                Map<String, String> fields = new HashMap<>();
                for (String token : block.split(",\"|\",")) {
                    token = token.trim();
                    int colon = token.indexOf(':');
                    if (colon < 0) continue;
                    String key = token.substring(0, colon).trim().replace("\"", "");
                    String val = token.substring(colon + 1).trim();
                    if (val.startsWith("\"")) val = val.substring(1, val.length() - 1);
                    fields.put(key, val);
                }
                int id = Integer.parseInt(fields.get("id"));
                Item it = new Item(id,
                    fields.get("name"), fields.get("category"),
                    fields.get("location"), fields.get("date"),
                    fields.get("description"));
                it.claimed = Boolean.parseBoolean(fields.get("claimed"));
                items.add(it);
                if (id >= nextId) nextId = id + 1;
            }
        } catch (Exception e) {
            System.err.println("Failed to load data: " + e.getMessage());
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private void insertionSort() {
        for (int i = 1; i < items.size(); i++) {
            Item key = items.get(i);
            int j = i - 1;
            while (j >= 0 && items.get(j).date.compareTo(key.date) > 0) {
                items.set(j + 1, items.get(j));
                j--;
            }
            items.set(j + 1, key);
        }
    }

    private int binarySearch(int id) {
        int lo = 0, hi = items.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            int midId = items.get(mid).id;
            if (midId == id) return mid;
            if (midId < id) lo = mid + 1;
            else hi = mid - 1;
        }
        return -1;
    }
}
