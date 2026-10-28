package de.hsh.favs.sharewise;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GreetingController {
    @GetMapping("/greeting")
    public MessageResponse greeting() {
        return new MessageResponse("Hello, World!");
    }
}

record MessageResponse(String message) {}
