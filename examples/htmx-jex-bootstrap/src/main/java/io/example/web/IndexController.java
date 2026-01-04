package io.example.web;

import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.example.web.view.IndexView;

@Controller
class IndexController {

    @Get
    IndexView.Index home() {
        return new IndexView.Index();
    }
}
