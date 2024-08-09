package com.e2eq.framework.blob;

import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MongoDBDump {
    private final String mongoUri;
    private final String databaseName;
    private final String bucketName;
    private final GridFSBucket gridFSBucket;

    public MongoDBDump(String mongoUri, String databaseName, String bucketName) {
        this.mongoUri = mongoUri;
        this.databaseName = databaseName;
        this.bucketName = bucketName;
        this.gridFSBucket = GridFSBuckets.create(getDatabase());
    }

    public void dumpFiles(String outputPath) throws IOException {
        dumpFilesRecursively(new ObjectId(), outputPath);
    }

    private void dumpFilesRecursively(ObjectId parentId, String outputPath) throws IOException {
        MongoCollection<Document> filesCollection = getFilesCollection();
        MongoCursor<Document> cursor = filesCollection.find(new Document("metadata.parentId", parentId)).iterator();
        while (cursor.hasNext()) {
            Document fileDoc = cursor.next();
            String filename = fileDoc.getString("filename");
            ObjectId fileId = fileDoc.getObjectId("_id");
            if (fileDoc.containsKey("contentType") && fileDoc.getString("contentType").equals("application/directory")) {
                // Create a directory for the subfolder
                Path subfolderPath = Paths.get(outputPath, filename);
                Files.createDirectories(subfolderPath);
                // Recursively dump the contents of the subfolder
                dumpFilesRecursively(fileId, subfolderPath.toString());
            } else {
                // Dump the file to the output directory
                FileOutputStream outputStream = new FileOutputStream(Paths.get(outputPath, filename).toFile());
                gridFSBucket.downloadToStream(fileId, outputStream);
                outputStream.close();
            }
        }
    }

    private MongoDatabase getDatabase() {
        return MongoClients.create(mongoUri).getDatabase(databaseName);
    }

    private MongoCollection<Document> getFilesCollection() {
        return getDatabase().getCollection(bucketName + ".files");
    }
}
