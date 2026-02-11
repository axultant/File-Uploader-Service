package kaspi.lab.uploadService.repository;

import kaspi.lab.uploadService.domain.FileEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface FileRepository extends ReactiveCrudRepository<FileEntity, UUID> {
    Mono<FileEntity> findByIdempotencyKey(String key);
}
