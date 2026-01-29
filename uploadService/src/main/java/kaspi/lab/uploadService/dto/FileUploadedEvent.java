package kaspi.lab.uploadService.dto;

import lombok.Builder;

import java.util.UUID;

@Builder
public record FileUploadedEvent(UUID fileId, String tempPath, String fileName, String contentType, long size) {
}
