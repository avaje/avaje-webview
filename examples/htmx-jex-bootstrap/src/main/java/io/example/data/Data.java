package io.example.data;

import java.util.List;
import java.util.stream.Stream;

public record Data(List<KBase> kbases) {

  public long kbaseCount() {
    return kbases.size();
  }

  public long taskCount() {
    return tasks().count();
  }

  public Stream<Task> tasks() {
    return kbases.stream().flatMap(kb -> kb.tasks().stream());
  }
}
