package kaspi.lab.uploadService.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("outbox")
public class OutboxEntity {

    @Id
    private Long id;
    private String eventType;
    private String payload;
    private String status;
    private Instant createdAt;
}
