package kaspi.lab.uploadService.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("files")
public class FileEntity implements Persistable<UUID> {
    @Id
    private UUID id;
    private String idempotencyKey;
    private String filename;
    private String contentType;
    private Long size;
    private String status;
    private String storagePath;
    private Instant createdAt;

    @Transient
    @Builder.Default
    private boolean isNewEntry = false;

    @Override
    public boolean isNew() { return isNewEntry || id == null; }
}
