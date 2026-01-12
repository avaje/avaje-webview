package io.example.web.view;

import io.example.data.Task;
import io.jstach.jstache.JStache;

import java.util.List;

public class Partial {

  @JStache(path = "partial/search-tasks")
  public record SearchTasks(Task first, List<Task> tasks) {}
}
