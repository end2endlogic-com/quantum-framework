package com.e2eq.tools;

import com.mongodb.ConnectionString;
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
import java.util.Arrays;
import java.util.Collections;

/**
 * CLI tool to export documents from a MongoDB collection to JSONL (one JSON object per line).
 *
 * Usage:
 *   java com.e2eq.tools.CollectionToJsonl <dbName> <collection> <userId> <password> [host=localhost] [port=27017] [filterJson='{}'] [output.jsonl]
 *
 * Notes:
 * - The optional filterJson must be a valid JSON object (e.g., '{"status":"ACTIVE"}').
 * - If output.jsonl is omitted, the tool writes to stdout.
 * - Authentication uses SCRAM-SHA-1/256 as negotiated by the server.
 */
public final class CollectionToJsonl {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: CollectionToJsonl <dbName> <collection> <userId> <password> [host=localhost] [port=27017] [filterJson='{}'] [output.jsonl]");
            System.exit(1);
        }
        String dbName = args[0];
        String collectionName = args[1];
        String user = args[2];
        String pass = args[3];
        String hostTmp = (args.length >= 5 && notBlank(args[4])) ? args[4] : "localhost";
        int portTmp = 27017;
        if (args.length >= 6 && notBlank(args[5])) {
            try { portTmp = Integer.parseInt(args[5]); } catch (NumberFormatException ignore) { /* keep default */ }
        }
        final String host = hostTmp;
        final int port = portTmp;
        String filterJson = (args.length >= 7 && notBlank(args[6])) ? args[6] : "{}";
        Path outputPath = (args.length >= 8 && notBlank(args[7])) ? Paths.get(args[7]) : null;

        // Build client settings using explicit credentials
        MongoCredential cred = MongoCredential.createCredential(user, dbName, pass.toCharArray());
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyToClusterSettings(b -> b.hosts(Collections.singletonList(new ServerAddress(host, port))))
                .credential(cred)
                .build();

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
