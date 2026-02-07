package kaspi.lab.uploadService.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import kaspi.lab.uploadService.config.AppUploadProperties;
import kaspi.lab.uploadService.domain.FileEntity;
import kaspi.lab.uploadService.domain.OutboxEntity;
import kaspi.lab.uploadService.dto.request.FileUploadRequest;
import kaspi.lab.uploadService.dto.response.FileUploadResponse;
import kaspi.lab.uploadService.dto.response.FileUploadedEvent;
import kaspi.lab.uploadService.mapper.FileMapper;
import kaspi.lab.uploadService.repository.FileRepository;
import kaspi.lab.uploadService.repository.OutboxRepository;
import kaspi.lab.uploadService.service.UploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadService {

    private final FileRepository fileRepository;
    private final OutboxRepository outboxRepository;
    private final FileMapper fileMapper;
    private final AppUploadProperties props;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public Mono<FileUploadResponse> processUpload(FilePart filePart, FileUploadRequest request, String idempotencyKey) {

        FileEntity entity = fileMapper.toEntity(request,idempotencyKey);

        assert entity.getId() != null;
        Path targetPath = Paths.get(props.getTempPath(), entity.getId().toString());
        entity.setStoragePath(targetPath.toString());

        log.info("Starting file upload: {} to {}", entity.getFilename(), targetPath);

        return filePart.transferTo(targetPath)
                .then(saveToDbAndOutbox(entity))
                .map(fileMapper::toResponse)
                .doOnSuccess(res -> {
                    assert res != null;
                    log.info("File successfully processed: {}", res.fileId());
                })
                .doOnError(err -> log.error("Failed to process file", err));
    }

    private Mono<FileEntity> saveToDbAndOutbox(FileEntity entity) {
        return fileRepository.save(entity)
                .flatMap(savedFile -> {
                    try {
                        FileUploadedEvent event = fileMapper.toEvent(savedFile, savedFile.getStoragePath());
                        String payload = objectMapper.writeValueAsString(event);

                        OutboxEntity outbox = OutboxEntity.builder()
                                .eventType("FILE_UPLOADED")
                                .payload(payload)
                                .status("NEW")
                                .createdAt(Instant.now())
                                .build();

                        return outboxRepository.save(outbox).thenReturn(savedFile);
                    } catch (Exception e) {
                        return Mono.error(new RuntimeException("Failed to serialize outbox event", e));
                    }
                });
    }


}
