package kaspi.lab.uploadService.dto.request;

import lombok.Builder;

@Builder
public record FileUploadRequest(
        String filename,
        String contentType,
        long size
) {}
