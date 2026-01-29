package kaspi.lab.uploadService.dto;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record FileUploadResponse(
        UUID fileId,
        String fileName,
        String status,
        String message,
        Instant timeStamp
) {
}
