package org.yuktisetu.adminservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication
// Scoped to the contexts this service owns or reaches, instead of the old
// blanket org.yuktisetu.db. The blanket scan mapped every entity on the
// classpath, so one bad column broke startup in four services at once.
// These lists shrink on their own as cross-context dependencies are cut.
@EntityScan(basePackages = {"org.yuktisetu.identity.db", "org.yuktisetu.institute.db", "org.yuktisetu.profile.db", "org.yuktisetu.drive.db", "org.yuktisetu.messaging.db"})
@EnableJpaRepositories(basePackages = {"org.yuktisetu.identity.repository", "org.yuktisetu.institute.repository", "org.yuktisetu.profile.repository", "org.yuktisetu.drive.repository", "org.yuktisetu.messaging.repository"})
@ComponentScan(basePackages = {"org.yuktisetu.adminservice", "org.yuktisetu.core"})
@EnableAsync
// Required for OutboxRelay's @Scheduled sweep. Without it the relay bean is
// created and its method is simply never invoked -- the outbox stays PENDING
// and nothing reports a problem.
@EnableScheduling
@EnableMethodSecurity
public class AdminServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminServiceApplication.class, args);
    }

}
