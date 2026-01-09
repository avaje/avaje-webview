package io.example.data;

import java.util.List;

public record KBase(KBaseMeta meta, List<Task> tasks) {
  @Override
  public String toString() {
    return meta.name() + " tasks:" + tasks.size();
  }
}
