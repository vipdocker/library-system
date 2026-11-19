package com.example.library;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 图书馆管理系统后端入口（design-v0.1）。
 * 数据库父目录由 {@link com.example.library.common.config.DatabaseDirectoryInitializer} 在容器早期创建。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LibraryApplication {

    public static void main(String[] args) {
        SpringApplication.run(LibraryApplication.class, args);
    }
}