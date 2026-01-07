package io.example.service;

import io.avaje.jsonb.Jsonb;
import io.example.data.*;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

final class DataLoader {

    public static final File[] EMPTY_DIR = {};
    private final System.Logger log = System.getLogger("app");

    private final Jsonb jsonb;

    DataLoader(Jsonb jsonb) {
        this.jsonb = jsonb;
    }

    Data load(File dataDirectory) {

        List<KBase> kBaseList = new ArrayList<>();
        if (dataDirectory.exists()) {
            for (File kbaseDir : fileList(dataDirectory)) {
                if (kbaseDir.exists() && kbaseDir.isDirectory()) {

                    KBaseMeta kBase = readKbase(kbaseDir);
                    log.log(System.Logger.Level.INFO, "read kbase {0}", kBase);

                    List<Task> tasks = new ArrayList<>();
                    for (File taskDir : fileList(kbaseDir)) {
                        if (taskDir.isDirectory()) {
                            TaskMeta taskMeta = readTaskMeta(taskDir);
                            log.log(System.Logger.Level.INFO, "read taskMeta {0}", taskMeta);

                            String previewContent = readPreview(taskDir);
                            tasks.add(new Task(taskMeta, previewContent, taskMeta.allSearchKeywords()));
                        }
                    }
                    kBaseList.add(new KBase(kBase, tasks));
                }
            }
        }

        return new Data(kBaseList);
    }

    private File[] fileList(File dir) {
        File[] files = dir.listFiles();
        return files != null ? files : EMPTY_DIR;
    }

    private String readPreview(File taskDir) {
        File file = new File(taskDir, "preview.html");
        if (file.exists()) {
            try {
                return Files.readString(file.toPath());
            } catch (IOException e) {
                return "";
            }
        }
        return "";
    }

    private TaskMeta readTaskMeta(File taskDir) {
        try(var is = new FileInputStream(new File(taskDir, "$meta.json"))) {
            return jsonb.type(TaskMeta.class).fromJson(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private KBaseMeta readKbase(File kbaseDir) {
        try(var is = new FileInputStream(new File(kbaseDir, "kbase.json"))) {
            return jsonb.type(KBaseMeta.class).fromJson(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
