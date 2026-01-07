package io.example.service;

import io.avaje.inject.Component;
import io.avaje.inject.PostConstruct;
import io.avaje.jsonb.Jsonb;
import io.example.data.Data;
import io.example.data.Task;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Component
public class DataService {

    private static final System.Logger log = System.getLogger("app");

    private final Jsonb jsonb;
    public Data data = new Data(List.of());

    DataService(Jsonb jsonb) {
        this.jsonb = jsonb;
    }

    public Data data() {
        return data;
    }

    void refreshData(Data data) {
        this.data = data;
    }

    @PostConstruct
    void initialLoad() {
        data = loadPath(System.getProperty("data.dir", "data"))
                .or(() -> loadPath("examples/htmx-jex-bootstrap/data"))
                .orElse(new Data(List.of()));
    }

    private Optional<Data> loadPath(String path) {
        File dataDir = new File(path);
        if (!dataDir.exists()) {
            return Optional.empty();
        }
        log.log(System.Logger.Level.DEBUG, "Load data from {0}", dataDir.getAbsolutePath());
        var dataLoader = new DataLoader(jsonb);
        return Optional.of(dataLoader.load(dataDir));
    }

    public List<Task> searchTasks(String search, int limit) {
        if (search == null) return List.of();
        String[] tokens = asTokens(search);
        return searchTasks(tokens, limit);
    }

    static String[] asTokens(String search) {
        String[] tokens = search.split(" ");
        return Stream.of(tokens).map(String::toLowerCase).toList().toArray(new String[0]);
    }

    private List<Task> searchTasks(String[] tokens, int limit) {
        if (tokens == null || tokens.length == 0) {
            return List.of();
        }
        return data.kbases().stream()
                .flatMap(kb -> kb.tasks().stream())
                .filter(task -> task.matchAll(tokens))
                .sorted()
                .limit(limit)
                .toList();
    }
}
