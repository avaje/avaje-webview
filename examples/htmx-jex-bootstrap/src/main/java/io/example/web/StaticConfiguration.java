package io.example.web;

import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.inject.PreDestroy;
import io.avaje.jex.staticcontent.StaticContent;
import jakarta.inject.Named;

@Factory
class StaticConfiguration {

    @PreDestroy
    void close() {
        System.out.println("StaticConfiguration close()");
    }

    @Bean
    @Named
    StaticContent favIcon() {
        return StaticContent.ofClassPath("/static/favicon.ico")
                .route("/favicon.ico")
                .build();
    }

    @Bean @Named
    StaticContent staticContent() {
        return StaticContent.ofClassPath("/static/")
                .route("/static/*")
                .directoryIndex("favicon.ico")
                .build();
    }
}
