package org.yuktisetu.adminservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication
@EntityScan(basePackages = "org.yuktisetu.db")
@EnableJpaRepositories(basePackages = "org.yuktisetu.repository")
@ComponentScan(basePackages = {"org.yuktisetu.adminservice", "org.yuktisetu.core"})
@EnableAsync
@EnableMethodSecurity
public class AdminServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminServiceApplication.class, args);
    }

}
