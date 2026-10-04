package devPilot.backend.DTO;

public record CitationDto(
        String filePath,
        Integer starting,
        Integer endLine,
        String  language) {
}
