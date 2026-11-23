package com.lcdw.springai.docmind01.Controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendController {
    @GetMapping("/verify")
    public String verify() {
        return "forward:/index.html";
    }
}
