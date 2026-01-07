package io.example.service;

import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import io.example.data.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;

final class DataLoader {

    private final JsonType<TaskMeta> metaJsonType;
    private final JsonType<KBaseMeta> kBaseMetaJsonType;

    DataLoader(Jsonb jsonb) {
        this.metaJsonType = jsonb.type(TaskMeta.class);
        this.kBaseMetaJsonType = jsonb.type(KBaseMeta.class);
    }

    static Data load(Jsonb jsonb, File dataDirectory) {
        return new DataLoader(jsonb).load(dataDirectory);
    }

    private Data load(File dataDirectory) {
        if (!dataDirectory.exists()) {
            return new Data(List.of());
        }

        List<KBase> kBases = fileStream(dataDirectory)
                .filter(File::isDirectory)
                .map(this::loadKBase)
                .toList();

        return new Data(kBases);
    }

    private KBase loadKBase(File kbaseDir) {
        KBaseMeta kBase = readKbase(kbaseDir);
        List<Task> list = fileStream(kbaseDir)
                .filter(File::isDirectory)
                .map(this::loadTask)
                .toList();

        return new KBase(kBase, list);
    }

    private Task loadTask(File taskDir) {
        TaskMeta taskMeta = readTaskMeta(taskDir);
        String previewContent = readPreview(taskDir);
        return new Task(taskMeta, previewContent, taskMeta.allSearchKeywords());
    }

    private Stream<File> fileStream(File dir) {
        File[] files = dir.listFiles();
        return files != null ? Stream.of(files) : Stream.of();
    }

    private String readPreview(File taskDir) {
        var file = new File(taskDir, "preview.html");
        if (!file.exists()) {
            return "";
        }
        try {
            return Files.readString(file.toPath());
        } catch (IOException e) {
            return "";
        }
    }

    private TaskMeta readTaskMeta(File taskDir) {
        try (var is = new FileInputStream(new File(taskDir, "$meta.json"))) {
            return metaJsonType.fromJson(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private KBaseMeta readKbase(File kbaseDir) {
        try (var is = new FileInputStream(new File(kbaseDir, "kbase.json"))) {
            return kBaseMetaJsonType.fromJson(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
