package de.hsh.favs.sharewise;

import de.hsh.favs.sharewise.shared.infra.config.ShareWiseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ShareWiseProperties.class)
public class ShareWiseApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShareWiseApplication.class, args);
    }

}
