package io.github.jasperzxy.javamanus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JavaManusApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaManusApplication.class, args);
    }
}
