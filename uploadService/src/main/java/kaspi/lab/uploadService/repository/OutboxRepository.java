package kaspi.lab.uploadService.repository;

import kaspi.lab.uploadService.domain.OutboxEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface OutboxRepository extends ReactiveCrudRepository<OutboxEntity, Long> {
    Flux<OutboxEntity> findAllByStatus(String status);
}
