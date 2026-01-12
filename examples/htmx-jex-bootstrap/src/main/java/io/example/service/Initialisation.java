package io.example.service;

import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.jsonb.Jsonb;

@Factory
final class Initialisation {

  @Bean
  Jsonb jsonb() {
    return Jsonb.builder().build();
  }
}
