package kaspi.lab.uploadService.scheduler;

import kaspi.lab.uploadService.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelayString = "${app.upload.outbox-check-interval:5000}")
    public void processOutbox() {
        outboxRepository.findAllByStatus("NEW")
                .flatMap(event -> {
                    log.info("Relaying event {} to Kafka", event.getId());

                    return Mono.fromFuture(kafkaTemplate.send("file-uploaded-topic", event.getPayload()).toCompletableFuture())
                            .flatMap(result -> {
                                event.setStatus("PROCESSED");
                                return outboxRepository.save(event);
                            })
                            .onErrorResume(e -> {
                                log.error("Failed to relay event {}", event.getId(), e);

                                return Mono.empty();
                            });
                }).subscribe();
    }
}
