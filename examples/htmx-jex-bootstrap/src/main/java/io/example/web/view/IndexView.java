package io.example.web.view;

import io.jstach.jstache.JStache;

public final class IndexView {

    @JStache(path = "index")
    public record Index(){}
}
