package com.elibrary;

import org.mybatis.spring.annotation.MapperScan;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** E-Library 應用程式進入點，同時啟用排程工作、Mapper 掃描與型別化設定。 */
@EnableScheduling
@MapperScan(basePackages = "com.elibrary", annotationClass = Mapper.class)
@ConfigurationPropertiesScan
@SpringBootApplication
public class ELibraryApplication {
    public static void main(String[] args) {
        SpringApplication.run(ELibraryApplication.class, args);
    }
}
