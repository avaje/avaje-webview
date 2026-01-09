package io.example.data;

import io.avaje.jsonb.Json;

@Json
public record TaskMeta(
    String type, String displayName, String searchKeywords, int priority, String description) {
  public String allSearchKeywords() {
    return searchKeywords == null || searchKeywords.isBlank()
        ? displayName.toLowerCase()
        : (displayName + " " + searchKeywords).toLowerCase();
  }
}
