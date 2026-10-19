package com.navan.assignment;

import java.io.File;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NavanApplication {

    public static void main(String[] args) {
        ensureSqliteDirectory();
        SpringApplication.run(NavanApplication.class, args);
    }

    private static void ensureSqliteDirectory() {
        String path = System.getenv().getOrDefault("NAVAN_DB_PATH", "./data/navan.db");
        File dbFile = new File(path);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }
}
