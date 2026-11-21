package com.elibrary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** E-Library 应用程式进入点，同时启用排程工作与型别化设定。 */
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class ELibraryApplication {
    /** 启动 Spring Boot 容器与所有排程、Web、持久化元件。 */
    public static void main(String[] args) {
        SpringApplication.run(ELibraryApplication.class, args);
    }
}
