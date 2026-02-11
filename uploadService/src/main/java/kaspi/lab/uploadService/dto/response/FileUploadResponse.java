package kaspi.lab.uploadService.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record FileUploadResponse(
        UUID fileId,
        String filename,
        String status,
        String message,
        Instant timestamp
) {}
