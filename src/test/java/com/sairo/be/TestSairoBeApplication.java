package com.sairo.be;

import org.springframework.boot.SpringApplication;

public class TestSairoBeApplication {

    public static void main(String[] args) {
        SpringApplication.from(SairoBeApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
