package com.wenxian.game.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.GameState;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class SaveStore {
    private final ObjectMapper objectMapper;
    private final Path dataFile;
    private Database database = new Database();

    public SaveStore(ObjectMapper objectMapper, @Value("${game.storage.path}") String storagePath) {
        this.objectMapper = objectMapper;
        this.dataFile = Path.of(storagePath).toAbsolutePath().normalize();
    }

    @PostConstruct
    void load() throws IOException {
        if (!Files.exists(dataFile)) return;
        database = objectMapper.readValue(dataFile.toFile(), Database.class);
        if (database.saves == null) database.saves = new LinkedHashMap<>();
    }

    public synchronized StoredSave find(String saveId) {
        return database.saves.get(saveId);
    }

    public synchronized StoredSave put(String saveId, GameState state, long revision) {
        StoredSave save = new StoredSave(state, revision, Instant.now());
        database.saves.put(saveId, save);
        prune();
        persist();
        return save;
    }

    private void prune() {
        while (database.saves.size() > 100) {
            String oldest = database.saves.entrySet().stream()
                    .min(Comparator.comparing(entry -> entry.getValue().updatedAt))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) return;
            database.saves.remove(oldest);
        }
    }

    private void persist() {
        try {
            Path directory = dataFile.getParent();
            if (directory != null) Files.createDirectories(directory);
            Path temporary = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), database);
            try {
                Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("无法写入游戏存档", error);
        }
    }

    public static class Database {
        public Map<String, StoredSave> saves = new LinkedHashMap<>();
    }

    public static class StoredSave {
        public GameState state;
        public long revision;
        public Instant updatedAt;

        public StoredSave() {}

        public StoredSave(GameState state, long revision, Instant updatedAt) {
            this.state = state;
            this.revision = revision;
            this.updatedAt = updatedAt;
        }
    }
}
