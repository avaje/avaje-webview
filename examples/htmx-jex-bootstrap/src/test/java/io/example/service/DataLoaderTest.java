package io.example.service;

import io.avaje.jsonb.Jsonb;
import io.example.data.Data;
import io.example.data.KBase;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataLoaderTest {

    @Test
    void load() {

        Data data = loadTestData();

        List<KBase> kbases = data.kbases();
        assertThat(kbases).hasSize(2);
    }

    static Data loadTestData() {
        var parentFile = new File(".").getParentFile();
        var dir = new File(parentFile, "data");
        return DataLoader.load(Jsonb.builder().build(), dir);
    }
}