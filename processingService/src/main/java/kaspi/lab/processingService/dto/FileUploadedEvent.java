package kaspi.lab.processingService.dto;

import java.util.UUID;

public record FileUploadedEvent(
        UUID fileId,
        String tempPath,
        String filename,
        String contentType,
        long size
) {}