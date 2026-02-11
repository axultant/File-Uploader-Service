package kaspi.lab.processingService.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import kaspi.lab.processingService.domain.FileEntity;
import kaspi.lab.processingService.dto.FileUploadedEvent;
import kaspi.lab.processingService.repository.FileRepository;
import kaspi.lab.processingService.service.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileProcessingConsumer {

    private final ObjectMapper objectMapper;
    private final MinioService minioService;
    private final FileRepository fileRepository;

    @KafkaListener(topics = "file-uploaded-topic", groupId = "processing-group")
    public void consume(String message) {
        log.info("DEBUG: Сообщение получено из Kafka: {}", message);

        Mono.fromCallable(() -> objectMapper.readValue(message, FileUploadedEvent.class))
                .flatMap(event -> {
                    Path tempFile = Paths.get(event.tempPath());
                    String objectName = event.fileId().toString(); // Имя файла в MinIO будет UUID

                    return minioService.uploadFile(tempFile, event.contentType(), objectName)
                            .flatMap(uploadedPath -> {
                                // Файл загружен, обновляем статус в БД
                                return fileRepository.findById(event.fileId())
                                        .flatMap(entity -> {
                                            entity.setStatus("COMPLETED");
                                            entity.setStoragePath("minio://" + uploadedPath);
                                            return fileRepository.save(entity);
                                        });
                            })
                            .doOnSuccess(saved -> {
                                log.info("File processed successfully. ID: {}", saved.getId());
                                deleteTempFile(tempFile); // Удаляем файл с диска
                            })
                            .doOnError(err -> {
                                log.error("Error processing file: {}", event.fileId(), err);
                                updateStatusToFailed(event.fileId());
                            });
                })
                .subscribe(); // Запускаем реактивную цепочку
    }

    private void deleteTempFile(Path path) {
        try {
            Files.deleteIfExists(path);
            log.info("Deleted temp file: {}", path);
        } catch (IOException e) {
            log.warn("Could not delete temp file: {}", path, e);
        }
    }

    private void updateStatusToFailed(java.util.UUID id) {
        fileRepository.findById(id)
                .flatMap(entity -> {
                    entity.setStatus("FAILED");
                    return fileRepository.save(entity);
                }).subscribe();
    }
}