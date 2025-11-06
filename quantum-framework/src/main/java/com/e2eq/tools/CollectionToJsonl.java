package com.e2eq.tools;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;

/**
 * CLI tool to export documents from a MongoDB collection to JSONL (one JSON object per line).
 *
 * Usage (two forms):
 * <pre>{@code
 * 1) Without auth (e.g., local dev):
 *    java com.e2eq.tools.CollectionToJsonl <dbName> <collection> [host=localhost] [port=27017] [filterJson='{}'] [output.jsonl]
 * 2) With auth:
 *    java com.e2eq.tools.CollectionToJsonl <dbName> <collection> <userId> <password> [host=localhost] [port=27017] [filterJson='{}'] [output.jsonl]
 * }</pre>
 *
 * Notes:
 * - The optional filterJson must be a valid JSON object (e.g., '{"status":"ACTIVE"}').
 * - If output.jsonl is omitted, the tool writes to stdout.
 * - To explicitly skip credentials while still providing host/port, you may pass '-' '-' for user and password.
 */
public final class CollectionToJsonl {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage (no auth):  CollectionToJsonl <dbName> <collection> [host] [port] [filterJson] [output.jsonl]\n" +
                    "Usage (with auth): CollectionToJsonl <dbName> <collection> <userId> <password> [host] [port] [filterJson] [output.jsonl]\n" +
                    "Tip: Use '-' '-' to skip user/password when providing host/port.");
            System.exit(1);
        }
        String dbName = args[0];
        String collectionName = args[1];

        String user = null;
        String pass = null;
        int idx; // current index for optional args
        if (args.length >= 4 && notBlank(args[2]) && notBlank(args[3]) && !"-".equals(args[2]) && !"-".equals(args[3])) {
            // Credentials provided
            user = args[2];
            pass = args[3];
            idx = 4;
        } else {
            // No credentials; optional args start at index 2
            idx = 2;
        }

        String host = (args.length > idx && notBlank(args[idx])) ? args[idx] : "localhost";
        idx++;
        int port = 27017;
        if (args.length > idx && notBlank(args[idx])) {
            try { port = Integer.parseInt(args[idx]); } catch (NumberFormatException ignore) { /* keep default */ }
        }
        idx++;
        String filterJson = (args.length > idx && notBlank(args[idx])) ? args[idx] : "{}";
        idx++;
        Path outputPath = (args.length > idx && notBlank(args[idx])) ? Paths.get(args[idx]) : null;

        // Build client settings; include credentials only if provided
        final String h = host;
        final int p = port;
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyToClusterSettings(b -> b.hosts(Collections.singletonList(new ServerAddress(h, p))));
        if (user != null && pass != null) {
            MongoCredential cred = MongoCredential.createCredential(user, dbName, pass.toCharArray());
            builder.credential(cred);
        }
        MongoClientSettings settings = builder.build();

        try (MongoClient client = MongoClients.create(settings)) {
            export(client, dbName, collectionName, filterJson, outputPath);
        }
    }

    private static void export(MongoClient client, String dbName, String collectionName, String filterJson, Path outputPath) throws IOException {
        MongoDatabase db = client.getDatabase(dbName);
        MongoCollection<Document> col = db.getCollection(collectionName);

        Document filter;
        try {
            filter = Document.parse(filterJson == null ? "{}" : filterJson);
        } catch (Exception e) {
            System.err.println("Invalid filter JSON: " + e.getMessage());
            System.exit(2);
            return; // unreachable, but required by compiler
        }

        FindIterable<Document> it = col.find(filter);

        if (outputPath == null) {
            // stdout
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
                for (Document d : it) {
                    out.write(d.toJson());
                    out.write('\n');
                }
                out.flush();
            }
        } else {
            // file
            if (outputPath.getParent() != null) Files.createDirectories(outputPath.getParent());
            try (BufferedWriter out = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Document d : it) {
                    out.write(d.toJson());
                    out.write('\n');
                }
            }
            System.out.printf("Export complete to %s%n", outputPath.toAbsolutePath());
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
