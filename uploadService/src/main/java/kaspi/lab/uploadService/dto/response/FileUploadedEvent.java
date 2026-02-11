package kaspi.lab.uploadService.dto.response;

import lombok.Builder;

import java.util.UUID;

@Builder
public record FileUploadedEvent(
        UUID fileId,
        String tempPath,
        String filename,
        String contentType,
        long size
) {}