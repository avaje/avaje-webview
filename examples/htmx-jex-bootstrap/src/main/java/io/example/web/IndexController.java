package io.example.web;

import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.example.web.view.Page;

@Controller
final class IndexController {

    @Get
    Page.Index home() {
        return new Page.Index();
    }
}
