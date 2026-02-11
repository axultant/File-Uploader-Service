package kaspi.lab.uploadService.mapper;

import kaspi.lab.uploadService.domain.FileEntity;
import kaspi.lab.uploadService.dto.request.FileUploadRequest;
import kaspi.lab.uploadService.dto.response.FileUploadResponse;
import kaspi.lab.uploadService.dto.response.FileUploadedEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.util.UUID;

@Mapper(componentModel = "spring", imports = {UUID.class, Instant.class})
public interface FileMapper {

    @Mapping(target = "id", expression = "java(UUID.randomUUID())")
    @Mapping(target = "status", constant = "PENDING")
    @Mapping(target = "createdAt", expression = "java(Instant.now())")
    @Mapping(target = "isNewEntry", constant = "true")
    @Mapping(target = "idempotencyKey", source = "key")
    @Mapping(target = "storagePath", ignore = true)
    FileEntity toEntity(FileUploadRequest request, String key);

    @Mapping(target = "fileId", source = "id")
    @Mapping(target = "timestamp", expression = "java(Instant.now())")
    FileUploadResponse toResponse(FileEntity entity);

    @Mapping(target = "fileId", source = "entity.id")
    @Mapping(target = "tempPath", source = "fullPath")
    FileUploadedEvent toEvent(FileEntity entity, String fullPath);
}
