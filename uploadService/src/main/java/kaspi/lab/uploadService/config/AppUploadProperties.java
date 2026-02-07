package kaspi.lab.uploadService.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Configuration
@Validated
@ConfigurationProperties(prefix = "app.upload")
public class AppUploadProperties {

    @NotBlank(message = "Временный путь (temp-path) должен быть указан")
    private String tempPath;

    @NotNull(message = "TTL идемпотентности должен быть указан")
    @Min(value = 60, message = "TTL должен быть не менее 60 секунд")
    private Long idempotencyTtl;
}
