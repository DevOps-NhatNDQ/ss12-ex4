package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
@RestController
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @GetMapping("/")
    public Map<String, Object> index() {
        Map<String, Object> response = new HashMap<>();
        response.put("service", "Spring Boot VPS Deployment");
        response.put("status", "HEALTHY");
        response.put("message", "Container is running successfully on remote VPS!");
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }
}
