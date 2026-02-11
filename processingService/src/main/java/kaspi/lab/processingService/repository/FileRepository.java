package kaspi.lab.processingService.repository;

import kaspi.lab.processingService.domain.FileEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import java.util.UUID;

public interface FileRepository extends R2dbcRepository<FileEntity, UUID> {
}