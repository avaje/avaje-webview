package io.example.data;

public record Task(TaskMeta meta, String preview, String all) implements Comparable<Task> {

  public String displayName() {
    return meta.displayName();
  }

  public String type() {
    return meta.type();
  }

  public String description() {
    return meta.description();
  }

  public boolean matchAll(String[] tokens) {
    for (String token : tokens) {
      if (!all.contains(token)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int compareTo(Task other) {
    return Integer.compare(meta.priority(), other.meta.priority());
  }
}
