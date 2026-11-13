package io.github.illuseahashmap.designhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Flow Admin BE. The Design code keeps the Java packages it had in Flow Engine, so the scan covers the whole
 * {@code io.github.illuseahashmap} namespace.
 */
@SpringBootApplication(scanBasePackages = "io.github.illuseahashmap")
public class DesignHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(DesignHubApplication.class, args);
    }
}
