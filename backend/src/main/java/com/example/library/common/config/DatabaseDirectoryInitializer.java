package com.example.library.common.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * SQLite 不会自动创建数据库父目录；在任何 DataSource Bean 实例化之前创建。
 * 以 BeanFactoryPostProcessor 实现，保证 main() 与 @SpringBootTest 两条路径都生效。
 */
@Configuration
public class DatabaseDirectoryInitializer {

    @Bean
    public static BeanFactoryPostProcessor databaseDirectoryCreator(Environment environment) {
        return beanFactory -> {
            String url = environment.getProperty("spring.datasource.url", "");
            String prefix = "jdbc:sqlite:";
            if (!url.startsWith(prefix)) {
                return;
            }
            String pathPart = url.substring(prefix.length());
            int query = pathPart.indexOf('?');
            if (query >= 0) {
                pathPart = pathPart.substring(0, query);
            }
            if (pathPart.isBlank() || pathPart.startsWith(":memory:")) {
                return;
            }
            Path parent = Path.of(pathPart).toAbsolutePath().getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException e) {
                    throw new UncheckedIOException("无法创建数据库目录: " + parent, e);
                }
            }
        };
    }
}