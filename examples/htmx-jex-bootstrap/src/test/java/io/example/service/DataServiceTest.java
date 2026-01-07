package io.example.service;

import io.avaje.jsonb.Jsonb;
import io.example.data.Data;
import io.example.data.Task;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataServiceTest {

    DataService dataService = init();

    DataService init() {
        var dataService = new DataService(Jsonb.builder().build());
        dataService.refreshData(DataLoaderTest.loadTestData());
        return dataService;
    }

    @Test
    void searchTasks_expect_single() {
        List<Task> results = dataService.searchTasks("sdk", 10);
        assertThat(results).hasSize(1);

        results = dataService.searchTasks("sdk java maven", 10);
        assertThat(results).hasSize(1);

        results = dataService.searchTasks("maven sdk", 10);
        assertThat(results).hasSize(1);
    }

    @Test
    void searchTasks_multi() {
        List<Task> results = dataService.searchTasks("mvn", 10);
        assertThat(results).hasSize(2);
    }

    @Test
    void searchTasks_multi_with_limit() {
        List<Task> results = dataService.searchTasks("mvn", 1);
        assertThat(results).hasSize(1);
    }

    @Test
    void asTokens() {
        assertThat(DataService.asTokens("Foo Bar bazz"))
                .containsOnly("foo", "bar", "bazz");
    }

    @Test
    void asTokens_when_empty() {
        assertThat(DataService.asTokens(""))
                .containsOnly("");
    }


    @Test
    void asTokens_when_one() {
        assertThat(DataService.asTokens("Foo"))
                .containsOnly("foo");
    }

}