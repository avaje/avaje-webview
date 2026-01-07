package io.example.web.view;

import io.jstach.jstache.JStache;

public class Page {

    @JStache(path = "index")
    public record Index(long kbaseCount, long taskCount){}

}
